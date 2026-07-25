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
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.RegionSummaryService;
import cn.net.rms.confluxmap.server.ServerConfig;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
//#if MC>=12105
//$$ import net.fabricmc.fabric.api.gametest.v1.GameTest;
//#else
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
//#endif
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
//#if MC<12105
import net.minecraft.test.GameTest;
//#endif
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
//#if MC>=12100
//$$ import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
//#endif

//#if MC>=12105
//$$ public final class PredictionSyncGameTest {
//#else
public final class PredictionSyncGameTest implements FabricGameTest {
//#endif
    private static final int FLOOR_SIZE = 64;
    private static final int BOMBARDMENT_FLOOR_SIZE = 32;
    private static final int TNT_COUNT = 4;
    private static final int TNT_FUSE_TICKS = 1;
    /** How far the floor is raised above the tallest column it has to outrank. */
    private static final int FLOOR_CLEARANCE = 8;
    /**
     * {@link cn.net.rms.confluxmap.core.net.DiffSpec}'s widest height tolerance, the
     * forest-equivalent case. A residual patch only carries a pixel whose surface differs from the
     * predicted baseline by more than this, so the residual floor has to clear the predicted
     * surface by more than this to be guaranteed a place in the patch.
     */
    private static final int DIFF_HEIGHT_TOLERANCE = 6;
    /**
     * Each test here writes stone into the tile it derives from its own test origin, and vanilla
     * runs a batch's tests concurrently a few dozen blocks apart - so those origins usually land in
     * one 256-block tile. Each test would then pick its floor Y independently from its own area's
     * tallest column, overwrite the others' pixels at the taller Y, and whichever test read the
     * shared tile would see a neighbor at the wrong height. The bombardment run has to stay beside
     * its own structure for the TNT to tick, so the round trips are the ones that step away - to
     * separate tiles, each further out than any batch layout spans.
     */
    private static final int ABSOLUTE_TILE_OFFSET = 8;
    private static final int RESIDUAL_TILE_OFFSET = 16;

    //#if MC>=12105
    //$$ @GameTest(maxTicks = 400)
    //#elseif MC>=12100
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    //#endif
    public void stoneFloorRoundTripsFromServerWorldToClientPrediction(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final int tileX = Math.floorDiv(context.getAbsolutePos(BlockPos.ORIGIN).getX(), 256) + ABSOLUTE_TILE_OFFSET;
        final int tileZ = Math.floorDiv(context.getAbsolutePos(BlockPos.ORIGIN).getZ(), 256) + ABSOLUTE_TILE_OFFSET;
        final int floorMinX = tileX * 256 + 96;
        final int floorMinZ = tileZ * 256 + 96;
        final int floorY = floorYAbove(
            world, floorMinX, floorMinZ, FLOOR_SIZE, highestSurfaceY(world, floorMinX, floorMinZ, FLOOR_SIZE)
        );

        placeStoneFloor(context, world, floorMinX, floorMinZ, FLOOR_SIZE, floorY);
        world.getChunkManager().save(true);

        final RoundTrip scenario = new RoundTrip(
            absolutePatchConfig(),
            Proto.PATCH_MODE_ABSOLUTE,
            7,
            new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "ConfluxSyncTest"),
            tileX, tileZ, floorMinX, floorMinZ, floorY
        );
        context.waitAndRun(5L, () -> verifyRoundTrip(context, server, world, scenario));
    }

    //#if MC>=12105
    //$$ @GameTest(maxTicks = 400)
    //#elseif MC>=12100
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    //#endif
    public void stoneFloorRoundTripsAsAResidualAgainstThePredictedBaseline(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final int tileX = Math.floorDiv(context.getAbsolutePos(BlockPos.ORIGIN).getX(), 256) + RESIDUAL_TILE_OFFSET;
        final int tileZ = Math.floorDiv(context.getAbsolutePos(BlockPos.ORIGIN).getZ(), 256) + RESIDUAL_TILE_OFFSET;
        final int tileOriginX = tileX * 256;
        final int tileOriginZ = tileZ * 256;
        final int floorMinX = tileOriginX + 96;
        final int floorMinZ = tileOriginZ + 96;

        require(context, NativeLib.available(), "native predictor was not initialized by the companion");
        final BaselineGrid baseline = predictedBaseline(world, tileOriginX, tileOriginZ);
        require(context, baseline != null, "client baseline sampling failed");
        // The server diffs the summary against this same derived grid, so raising the floor clear of
        // its tallest predicted column is what guarantees every floor pixel becomes a residual.
        final int highestPredicted = highestPredictedY(
            predictedDerived(baseline, world.getSeed(), tileOriginX, tileOriginZ),
            floorMinX - tileOriginX,
            floorMinZ - tileOriginZ,
            FLOOR_SIZE
        );
        final int floorY = floorYAbove(
            world,
            floorMinX,
            floorMinZ,
            FLOOR_SIZE,
            Math.max(highestSurfaceY(world, floorMinX, floorMinZ, FLOOR_SIZE), highestPredicted)
        );
        require(
            context,
            floorY - highestPredicted > DIFF_HEIGHT_TOLERANCE,
            "the build limit left the floor only " + (floorY - highestPredicted)
                + " above the predicted surface, which DiffSpec may report as unchanged"
        );

        placeStoneFloor(context, world, floorMinX, floorMinZ, FLOOR_SIZE, floorY);
        world.getChunkManager().save(true);

        final RoundTrip scenario = new RoundTrip(
            residualPatchConfig(),
            Proto.PATCH_MODE_RESIDUAL,
            11,
            new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000003"), "ConfluxResidualTest"),
            tileX, tileZ, floorMinX, floorMinZ, floorY
        );
        context.waitAndRun(5L, () -> verifyRoundTrip(context, server, world, scenario));
    }

    //#if MC>=12105
    //$$ @GameTest(maxTicks = 400)
    //#elseif MC>=12100
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    //#endif
    public void tntBombardmentReducesSynchronizedStoneSurface(final TestContext context) {
        new BombardmentRun(context).start();
    }

    /** One round-trip scenario: where its floor sits, and what patch shape the server should send. */
    private record RoundTrip(
        ServerConfig config,
        int expectedMode,
        int reqId,
        GameProfile profile,
        int tileX,
        int tileZ,
        int floorMinX,
        int floorMinZ,
        int floorY
    ) {
        int tileOriginX() {
            return tileX * 256;
        }

        int tileOriginZ() {
            return tileZ * 256;
        }
    }

    private static ServerConfig syncTestConfig() {
        final ServerConfig config = new ServerConfig();
        config.maxBytesPerSecondPerPlayer = 1 << 20;
        config.maxTilesPerRequest = 1;
        config.minRequestIntervalMs = 0;
        return config;
    }

    /**
     * Takes prediction out of the loop: every generated pixel ships, so the assertions cover the
     * transport and correction-application contract on its own. Vanilla drops these tests at a
     * random x/z (see {@code TestServer}'s {@code TEST_POS_XZ_RANGE}), and a residual patch's
     * contents are a function of the terrain there, so this is the shape that stays deterministic
     * without the caller having to reason about the baseline at all.
     */
    private static ServerConfig absolutePatchConfig() {
        final ServerConfig config = syncTestConfig();
        config.forceAbsolutePatches = true;
        return config;
    }

    /**
     * Keeps the production diffing path: the server subtracts its own cubiomes baseline and the
     * client re-derives the identical one to restore the pixels. That agreement is the part only an
     * end-to-end test can check, which is why this scenario exists alongside the absolute one - its
     * caller earns determinism by clearing {@link #DIFF_HEIGHT_TOLERANCE} instead.
     */
    private static ServerConfig residualPatchConfig() {
        return syncTestConfig();
    }

    private static void verifyRoundTrip(
        final TestContext context,
        final MinecraftServer server,
        final ServerWorld world,
        final RoundTrip scenario
    ) {
        require(context, NativeLib.available(), "native predictor was not initialized by the companion");

        final int tileOriginX = scenario.tileOriginX();
        final int tileOriginZ = scenario.tileOriginZ();
        final int floorMinX = scenario.floorMinX();
        final int floorMinZ = scenario.floorMinZ();
        final int floorY = scenario.floorY();
        final RegionSummaryService summaries = new RegionSummaryService(scenario.config());
        final ServerPlayerEntity player = testPlayer(server, world, scenario.profile());
        final List<Message> responses = new ArrayList<>();
        summaries.request(
            server,
            player,
            new MapViewReqC2S(
                scenario.reqId(),
                dimensionIndex(server, world),
                0,
                List.of(new MapViewReqC2S.TileReq(scenario.tileX(), scenario.tileZ(), 0L))
            ),
            responses::add
        );

        require(context, responses.size() == 1, "expected one server response, got " + responses);
        require(context, responses.get(0) instanceof MapPatchS2C, "expected MAP_PATCH, got " + responses.get(0));

        try {
            final MapPatchS2C patch = (MapPatchS2C) MsgCodec.decode(MsgCodec.encode(responses.get(0)));
            require(
                context,
                patch.mode() == scenario.expectedMode(),
                "expected patch mode " + scenario.expectedMode() + ", got " + patch.mode()
            );
            require(context, patch.reqId() == scenario.reqId(), "server did not preserve request id");
            require(
                context,
                patch.tileX() == scenario.tileX() && patch.tileZ() == scenario.tileZ(),
                "server returned the wrong tile"
            );

            final CorrectionTile correction = new CorrectionTile();
            correction.applyPatch(patch.tileRevision(), patch.presence(), PatchCodec.decode(patch.body()));
            final BaselineGrid baseline = predictedBaseline(world, tileOriginX, tileOriginZ);
            require(context, baseline != null, "client baseline sampling failed");
            final DerivedGrid derived = predictedDerived(baseline, world.getSeed(), tileOriginX, tileOriginZ);
            final int[] clientPixels = PredictedTileComposer.compose(
                derived,
                baseline,
                PredictionPalette.defaults(),
                correction,
                PredictionViewMode.EVERYWHERE,
                0
            );
            final int expectedStoneArgb = ShadingPipeline.applyShade(
                MapColorTable.argb(11),
                ShadingPipeline.heightShade(floorY, ShadingPipeline.REFERENCE_HEIGHT, true)
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
                        sample.surfaceY() == floorY,
                        "wrong stone surface height at " + pixelX + "," + pixelZ
                    );
                    require(context, sample.kind() == SurfaceKind.LAND.ordinal(), "stone floor was not classified as land");
                    require(context, sample.mapColorId() == 11, "stone floor used the wrong map color");
                    // LOD0 relief reads the (x-1, z+1) neighbor, so an edge pixel legitimately
                    // slopes against whatever lies just outside the floor. Every interior pixel's
                    // neighbor is floor stone at the same Y, and an absolute patch carries all of
                    // them, so its shade is flat and the map color reconstructs exactly - the one
                    // guarantee a residual patch could not make, because DiffSpec may drop a
                    // neighbor whose predicted height happens to fall inside its tolerance.
                    if (x > 0 && x < FLOOR_SIZE - 1 && z > 0 && z < FLOOR_SIZE - 1
                        && clientPixels[pixel] != expectedStoneArgb) {
                        final int neighborX = pixelX - 1;
                        final int neighborZ = pixelZ + 1;
                        final PatchCodec.Sample neighbor = correction.sampleAt(neighborZ * 256 + neighborX);
                        GameTestCompat.fail(
                            context,
                            "client did not reconstruct interior stone at " + pixelX + "," + pixelZ
                                + ": floorY=" + floorY
                                + " argb=" + Integer.toHexString(clientPixels[pixel])
                                + " expected=" + Integer.toHexString(expectedStoneArgb)
                                + " swNeighbor=" + neighborX + "," + neighborZ
                                + (neighbor == null
                                    ? " absent from patch"
                                    : " y=" + neighbor.surfaceY()
                                        + " kind=" + neighbor.kind()
                                        + " color=" + neighbor.mapColorId())
                        );
                    }
                }
            }
            context.complete();
        } catch (final ProtoException e) {
            GameTestCompat.fail(context, "sync protocol failed: " + e.getMessage());
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
            player = testPlayer(
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
                    patch.mode() == Proto.PATCH_MODE_ABSOLUTE,
                    "expected initial absolute patch, got mode " + patch.mode()
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
            if (patch.mode() == Proto.PATCH_MODE_ABSOLUTE) {
                decoded = PatchCodec.decode(patch.body());
            } else {
                require(
                    context,
                    patch.mode() == Proto.PATCH_MODE_UNCHANGED && patch.body().length == 0,
                    "expected absolute or unchanged patch, got mode " + patch.mode()
                );
                decoded = new PatchCodec.Patch(List.of());
            }
            correction.applyPatch(patch.tileRevision(), patch.presence(), decoded);
        }

        private int elevatedFloorY() {
            return PredictionSyncGameTest.elevatedFloorY(
                world, floorMinX, floorMinZ, BOMBARDMENT_FLOOR_SIZE
            );
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
            GameTestCompat.fail(context, "sync protocol failed: " + failure.getMessage());
        }

        private static int clampFloorStart(final int testCoordinate, final int tileOrigin) {
            return Math.max(
                tileOrigin + 1,
                Math.min(testCoordinate - BOMBARDMENT_FLOOR_SIZE / 2, tileOrigin + 255 - BOMBARDMENT_FLOOR_SIZE)
            );
        }
    }

    private static BaselineGrid predictedBaseline(
        final ServerWorld world,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        return LodSampling.sample(
            new NativeBaselineSampler(
                McVersions.toCubiomes(MinecraftVersion.current()).orElseThrow(), world.getSeed(), 0, 0
            ),
            false,
            0,
            tileOriginX,
            tileOriginZ
        );
    }

    /** Mirrors the grid {@code PatchBuilder} derives before diffing, canopy pass included. */
    private static DerivedGrid predictedDerived(
        final BaselineGrid baseline,
        final long seed,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        final DerivedGrid derived = BaselineDeriver.derive(baseline);
        CanopyStylizer.apply(derived, baseline, seed, 0, tileOriginX, tileOriginZ);
        return derived;
    }

    /** Tallest predicted column under the floor, addressed in tile-local pixels. */
    private static int highestPredictedY(
        final DerivedGrid derived,
        final int floorPixelX,
        final int floorPixelZ,
        final int floorSize
    ) {
        int highest = Integer.MIN_VALUE;
        for (int z = 0; z < floorSize; z++) {
            for (int x = 0; x < floorSize; x++) {
                highest = Math.max(
                    highest, derived.surfaceY[BaselineGrid.index(floorPixelX + x, floorPixelZ + z)]
                );
            }
        }
        return highest;
    }

    private static void placeStoneFloor(
        final TestContext context,
        final ServerWorld world,
        final int floorMinX,
        final int floorMinZ,
        final int floorSize,
        final int floorY
    ) {
        for (int z = 0; z < floorSize; z++) {
            for (int x = 0; x < floorSize; x++) {
                require(
                    context,
                    world.setBlockState(
                        new BlockPos(floorMinX + x, floorY, floorMinZ + z),
                        Blocks.STONE.getDefaultState(),
                        Block.NOTIFY_ALL
                    ),
                    "failed to place stone floor at " + x + "," + z
                );
            }
        }
    }

    /** Generates the floor's chunks, then reports the tallest real column in it. */
    private static int highestSurfaceY(
        final ServerWorld world,
        final int floorMinX,
        final int floorMinZ,
        final int floorSize
    ) {
        final int maxX = floorMinX + floorSize - 1;
        final int maxZ = floorMinZ + floorSize - 1;
        for (int chunkZ = floorMinZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
            for (int chunkX = floorMinX >> 4; chunkX <= maxX >> 4; chunkX++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
        int highest = world.getBottomY();
        for (int z = 0; z < floorSize; z++) {
            for (int x = 0; x < floorSize; x++) {
                highest = Math.max(
                    highest,
                    world.getTopY(Heightmap.Type.MOTION_BLOCKING, floorMinX + x, floorMinZ + z)
                );
            }
        }
        return highest;
    }

    private static int elevatedFloorY(
        final ServerWorld world,
        final int floorMinX,
        final int floorMinZ,
        final int floorSize
    ) {
        return floorYAbove(
            world, floorMinX, floorMinZ, floorSize, highestSurfaceY(world, floorMinX, floorMinZ, floorSize)
        );
    }

    private static int floorYAbove(
        final ServerWorld world,
        final int floorMinX,
        final int floorMinZ,
        final int floorSize,
        final int highest
    ) {
        final int floorY = Math.min(highest + FLOOR_CLEARANCE, world.getTopY() - 2);
        // Vanilla drops these tests at a random x/z, so the tallest column here is whatever the
        // generator produced. When terrain plus canopy reaches the build limit the clamp above puts
        // the floor at or below that column, and the server then summarizes the terrain instead of
        // the floor - which reads downstream as a neighbor at the wrong height, silently breaking
        // the flat-plateau shading the assertions rely on. Clearing the little that fits above the
        // clamped floor keeps the floor the top face at every pixel, at any position.
        if (floorY <= highest) {
            for (int y = floorY + 1; y < world.getTopY(); y++) {
                for (int z = 0; z < floorSize; z++) {
                    for (int x = 0; x < floorSize; x++) {
                        world.setBlockState(
                            new BlockPos(floorMinX + x, y, floorMinZ + z),
                            Blocks.AIR.getDefaultState(),
                            Block.NOTIFY_ALL
                        );
                    }
                }
            }
        }
        return floorY;
    }

    private static ServerPlayerEntity testPlayer(
        final MinecraftServer server,
        final ServerWorld world,
        final GameProfile profile
    ) {
        //#if MC>=12100
        //$$ return new ServerPlayerEntity(server, world, profile, SyncedClientOptions.createDefault());
        //#else
        return new ServerPlayerEntity(server, world, profile);
        //#endif
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
            GameTestCompat.fail(context, message);
        }
    }
}
