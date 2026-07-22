package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.BaselineDeriver;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.CanopyStylizer;
import cn.net.rms.confluxmap.core.predict.CorrectionTile;
import cn.net.rms.confluxmap.core.predict.DerivedGrid;
import cn.net.rms.confluxmap.core.predict.LodSampling;
import cn.net.rms.confluxmap.core.predict.MapColorTable;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictedTileComposer;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.RegionSummaryService;
import cn.net.rms.confluxmap.server.ServerConfig;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

public final class PredictionSyncGameTest implements FabricGameTest {
    private static final int FLOOR_SIZE = 64;
    private static final int BOMBARDMENT_FLOOR_SIZE = 32;
    private static final int TNT_COUNT = 4;
    private static final int TNT_FUSE_TICKS = 1;
    private static final int FLOOR_Y = 79;
    private static final int EXPECTED_SURFACE_Y = FLOOR_Y;
    private static final int EXPECTED_STONE_ARGB = ShadingPipeline.applyShade(
        MapColorTable.argb(11),
        ShadingPipeline.heightShade(EXPECTED_SURFACE_Y, ShadingPipeline.REFERENCE_HEIGHT, false)
    );

    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    public void stoneFloorRoundTripsFromServerWorldToClientPrediction(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final BlockPos testOrigin = context.getAbsolutePos(BlockPos.ORIGIN);
        final int tileX = Math.floorDiv(testOrigin.getX(), 256);
        final int tileZ = Math.floorDiv(testOrigin.getZ(), 256);
        final int tileOriginX = tileX * 256;
        final int tileOriginZ = tileZ * 256;
        final int floorMinX = tileOriginX + 96;
        final int floorMinZ = tileOriginZ + 96;

        for (int z = 0; z < FLOOR_SIZE; z++) {
            for (int x = 0; x < FLOOR_SIZE; x++) {
                world.setBlockState(
                    new BlockPos(floorMinX + x, FLOOR_Y, floorMinZ + z),
                    Blocks.STONE.getDefaultState(),
                    Block.NOTIFY_ALL
                );
            }
        }
        world.getChunkManager().save(true);

        context.waitAndRun(5L, () -> verifyRoundTrip(
            context, server, world, tileX, tileZ, tileOriginX, tileOriginZ, floorMinX, floorMinZ
        ));
    }

    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    public void tntBombardmentReducesSynchronizedStoneSurface(final TestContext context) {
        new BombardmentRun(context).start();
    }

    private static ServerConfig syncTestConfig() {
        final ServerConfig config = new ServerConfig();
        config.maxBytesPerSecondPerPlayer = 1 << 20;
        config.maxTilesPerRequest = 1;
        config.minRequestIntervalMs = 0;
        return config;
    }

    private static void verifyRoundTrip(
        final TestContext context,
        final MinecraftServer server,
        final ServerWorld world,
        final int tileX,
        final int tileZ,
        final int tileOriginX,
        final int tileOriginZ,
        final int floorMinX,
        final int floorMinZ
    ) {
        require(context, NativeLib.available(), "native predictor was not initialized by the companion");

        final RegionSummaryService summaries = new RegionSummaryService(syncTestConfig());
        final ServerPlayerEntity player = new ServerPlayerEntity(
            server,
            world,
            new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "ConfluxSyncTest")
        );
        final List<Message> responses = new ArrayList<>();
        summaries.request(
            server,
            player,
            new MapViewReqC2S(
                7, dimensionIndex(server, world), 0, List.of(new MapViewReqC2S.TileReq(tileX, tileZ, 0L))
            ),
            responses::add
        );

        require(context, responses.size() == 1, "expected one server response, got " + responses);
        require(context, responses.get(0) instanceof MapPatchS2C, "expected MAP_PATCH, got " + responses.get(0));

        try {
            final MapPatchS2C patch = (MapPatchS2C) MsgCodec.decode(MsgCodec.encode(responses.get(0)));
            require(
                context,
                patch.mode() == Proto.PATCH_MODE_RESIDUAL,
                "expected residual patch, got mode " + patch.mode()
            );
            require(context, patch.reqId() == 7, "server did not preserve request id");
            require(context, patch.tileX() == tileX && patch.tileZ() == tileZ, "server returned the wrong tile");

            final CorrectionTile correction = new CorrectionTile();
            correction.applyPatch(patch.tileRevision(), patch.presence(), PatchCodec.decode(patch.body()));
            final BaselineGrid baseline = LodSampling.sample(
                new NativeBaselineSampler(
                    McVersions.toCubiomes("1.17.1").orElseThrow(), world.getSeed(), 0, 0
                ),
                false,
                0,
                tileOriginX,
                tileOriginZ
            );
            require(context, baseline != null, "client baseline sampling failed");
            final DerivedGrid derived = BaselineDeriver.derive(baseline);
            CanopyStylizer.apply(derived, baseline, world.getSeed(), 0, tileOriginX, tileOriginZ);
            final int[] clientPixels = PredictedTileComposer.compose(
                derived,
                baseline,
                PredictionPalette.defaults(),
                correction,
                PredictionViewMode.EVERYWHERE,
                0
            );

            for (int z = 0; z < FLOOR_SIZE; z++) {
                for (int x = 0; x < FLOOR_SIZE; x++) {
                    final int pixelX = floorMinX + x - tileOriginX;
                    final int pixelZ = floorMinZ + z - tileOriginZ;
                    final int pixel = pixelZ * 256 + pixelX;
                    final PatchCodec.Sample sample = correction.sampleAt(pixel);
                    require(context, sample != null, "server omitted stone floor pixel " + pixelX + "," + pixelZ);
                    require(
                        context,
                        sample.surfaceY() == EXPECTED_SURFACE_Y,
                        "wrong stone surface height at " + pixelX + "," + pixelZ
                    );
                    require(context, sample.kind() == SurfaceKind.LAND.ordinal(), "stone floor was not classified as land");
                    require(context, sample.mapColorId() == 11, "stone floor used the wrong map color");
                    // Edge pixels legitimately receive slope shading from predicted terrain just
                    // outside the corrected floor. The interior has both diagonal slope samples
                    // on the flat stone surface and must reconstruct the exact map color.
                    if (x > 0 && x < FLOOR_SIZE - 1 && z > 0 && z < FLOOR_SIZE - 1) {
                        require(
                            context,
                            clientPixels[pixel] == EXPECTED_STONE_ARGB,
                            "client did not reconstruct interior stone at " + pixelX + "," + pixelZ
                        );
                    }
                }
            }
            context.complete();
        } catch (final ProtoException e) {
            context.throwGameTestException("sync protocol failed: " + e.getMessage());
        }
    }

    private static final class BombardmentRun {
        private final TestContext context;
        private final ServerWorld world;
        private final MinecraftServer server;
        private final int tileX;
        private final int tileZ;
        private final int tileOriginX;
        private final int tileOriginZ;
        private final int floorMinX;
        private final int floorY;
        private final int floorMinZ;
        private final RegionSummaryService summaries;
        private final ServerPlayerEntity player;
        private final CorrectionTile correction = new CorrectionTile();
        private int initialStoneCount;

        BombardmentRun(final TestContext context) {
            this.context = context;
            world = context.getWorld();
            server = world.getServer();
            final BlockPos testOrigin = context.getAbsolutePos(BlockPos.ORIGIN);
            tileX = Math.floorDiv(testOrigin.getX(), 256);
            tileZ = Math.floorDiv(testOrigin.getZ(), 256);
            tileOriginX = tileX * 256;
            tileOriginZ = tileZ * 256;
            floorMinX = clampFloorStart(testOrigin.getX(), tileOriginX);
            floorMinZ = clampFloorStart(testOrigin.getZ(), tileOriginZ);
            floorY = elevatedFloorY();
            summaries = new RegionSummaryService(syncTestConfig());
            player = new ServerPlayerEntity(
                server,
                world,
                new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000002"), "ConfluxTntSyncTest")
            );
        }

        void start() {
            require(context, NativeLib.available(), "native predictor was not initialized by the companion");
            placeStoneFloor();
            world.getChunkManager().save(true);
            context.waitAndRun(5L, this::syncInitialFloor);
        }

        private void syncInitialFloor() {
            try {
                final MapPatchS2C patch = requestPatch(8, 0L);
                require(
                    context,
                    patch.mode() == Proto.PATCH_MODE_RESIDUAL,
                    "expected initial residual patch, got mode " + patch.mode()
                );
                applyCorrectionPatch(patch);
                initialStoneCount = countWorldStone();
                final int synchronizedStone = countSynchronizedStone();
                require(
                    context,
                    initialStoneCount == BOMBARDMENT_FLOOR_SIZE * BOMBARDMENT_FLOOR_SIZE,
                    "stone floor was incomplete before bombardment: " + initialStoneCount
                );
                require(
                    context,
                    synchronizedStone == initialStoneCount,
                    "initial synchronization did not capture the complete stone floor: " + synchronizedStone
                );
                spawnTnt();
                context.waitAndRun(20L, this::saveBombardmentResult);
            } catch (final ProtoException e) {
                failProtocol(e);
            }
        }

        private void saveBombardmentResult() {
            final int remainingStone = countWorldStone();
            require(
                context,
                remainingStone < initialStoneCount,
                "fixed TNT bombardment did not remove any stone surface blocks"
            );
            world.getChunkManager().save(true);
            context.waitAndRun(5L, () -> syncBombardmentResult(remainingStone));
        }

        private void syncBombardmentResult(final int remainingStone) {
            try {
                applyCorrectionPatch(requestPatch(9, correction.revision()));
                final int synchronizedStone = countSynchronizedStone();
                require(
                    context,
                    synchronizedStone < initialStoneCount,
                    "client correction retained the pre-bombardment stone count"
                );
                require(
                    context,
                    synchronizedStone == remainingStone,
                    "stone reduction did not round-trip: server=" + remainingStone + ", client=" + synchronizedStone
                );
                context.complete();
            } catch (final ProtoException e) {
                failProtocol(e);
            }
        }

        private MapPatchS2C requestPatch(final int requestId, final long sinceRevision) throws ProtoException {
            final List<Message> responses = new ArrayList<>();
            summaries.request(
                server,
                player,
                new MapViewReqC2S(
                    requestId,
                    dimensionIndex(server, world),
                    0,
                    List.of(new MapViewReqC2S.TileReq(tileX, tileZ, sinceRevision))
                ),
                responses::add
            );
            require(context, responses.size() == 1, "expected one server response, got " + responses);
            require(context, responses.get(0) instanceof MapPatchS2C, "expected MAP_PATCH, got " + responses.get(0));
            return (MapPatchS2C) MsgCodec.decode(MsgCodec.encode(responses.get(0)));
        }

        private void applyCorrectionPatch(final MapPatchS2C patch) throws ProtoException {
            final PatchCodec.Patch decoded;
            if (patch.mode() == Proto.PATCH_MODE_RESIDUAL) {
                decoded = PatchCodec.decode(patch.body());
            } else {
                require(
                    context,
                    patch.mode() == Proto.PATCH_MODE_UNCHANGED && patch.body().length == 0,
                    "expected residual or unchanged patch, got mode " + patch.mode()
                );
                decoded = new PatchCodec.Patch(List.of());
            }
            correction.applyPatch(patch.tileRevision(), patch.presence(), decoded);
        }

        private int elevatedFloorY() {
            int result = world.getBottomY();
            for (int z = 0; z < BOMBARDMENT_FLOOR_SIZE; z++) {
                for (int x = 0; x < BOMBARDMENT_FLOOR_SIZE; x++) {
                    result = Math.max(
                        result,
                        world.getTopY(Heightmap.Type.MOTION_BLOCKING, floorMinX + x, floorMinZ + z) + 8
                    );
                }
            }
            return Math.min(result, world.getTopY() - 2);
        }

        private void placeStoneFloor() {
            for (int z = 0; z < BOMBARDMENT_FLOOR_SIZE; z++) {
                for (int x = 0; x < BOMBARDMENT_FLOOR_SIZE; x++) {
                    world.setBlockState(
                        new BlockPos(floorMinX + x, floorY, floorMinZ + z),
                        Blocks.STONE.getDefaultState(),
                        Block.NOTIFY_ALL
                    );
                }
            }
        }

        private void spawnTnt() {
            for (int index = 0; index < TNT_COUNT; index++) {
                final int gridX = index & 1;
                final int gridZ = index >>> 1;
                final double x = floorMinX + (gridX + 1) * BOMBARDMENT_FLOOR_SIZE / 3.0D;
                final double z = floorMinZ + (gridZ + 1) * BOMBARDMENT_FLOOR_SIZE / 3.0D;
                final TntEntity tnt = new TntEntity(world, x, floorY + 1.0D, z, null);
                tnt.setFuse(TNT_FUSE_TICKS);
                require(context, world.spawnEntity(tnt), "failed to spawn TNT " + (index + 1));
            }
        }

        private int countWorldStone() {
            int count = 0;
            for (int z = 0; z < BOMBARDMENT_FLOOR_SIZE; z++) {
                for (int x = 0; x < BOMBARDMENT_FLOOR_SIZE; x++) {
                    if (world.getBlockState(new BlockPos(floorMinX + x, floorY, floorMinZ + z)).isOf(Blocks.STONE)) {
                        count++;
                    }
                }
            }
            return count;
        }

        private int countSynchronizedStone() {
            int count = 0;
            for (int z = 0; z < BOMBARDMENT_FLOOR_SIZE; z++) {
                for (int x = 0; x < BOMBARDMENT_FLOOR_SIZE; x++) {
                    final int pixelX = floorMinX + x - tileOriginX;
                    final int pixelZ = floorMinZ + z - tileOriginZ;
                    final PatchCodec.Sample sample = correction.sampleAt(pixelZ * 256 + pixelX);
                    if (sample != null && sample.surfaceY() == floorY
                        && sample.kind() == SurfaceKind.LAND.ordinal() && sample.mapColorId() == 11) {
                        count++;
                    }
                }
            }
            return count;
        }

        private void failProtocol(final ProtoException failure) {
            context.throwGameTestException("sync protocol failed: " + failure.getMessage());
        }

        private static int clampFloorStart(final int testCoordinate, final int tileOrigin) {
            return Math.max(
                tileOrigin + 1,
                Math.min(testCoordinate - BOMBARDMENT_FLOOR_SIZE / 2, tileOrigin + 255 - BOMBARDMENT_FLOOR_SIZE)
            );
        }
    }

    private static int dimensionIndex(final MinecraftServer server, final ServerWorld expected) {
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (world == expected) {
                return index;
            }
            index++;
        }
        throw new IllegalStateException("test world is not registered on the server");
    }

    private static void require(final TestContext context, final boolean condition, final String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
