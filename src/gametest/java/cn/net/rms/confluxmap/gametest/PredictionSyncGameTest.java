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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public final class PredictionSyncGameTest implements FabricGameTest {
    private static final int FLOOR_SIZE = 64;
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

        final ServerConfig config = new ServerConfig();
        config.maxBytesPerSecondPerPlayer = 1 << 20;
        config.maxTilesPerRequest = 1;
        config.minRequestIntervalMs = 0;
        final RegionSummaryService summaries = new RegionSummaryService(config);
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
                    McVersions.toCubiomes("1.17.1").orElseThrow(), world.getSeed(), 0
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
