package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.PatchBuilder;
import cn.net.rms.confluxmap.server.SummaryView;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperCorrectionBaselineTest {
    @TempDir
    Path temporary;

    @Test
    void matchingVanillaPredictorUsesResidualBaseline() {
        NativeLib.init(temporary.resolve("confluxmap"));
        Assumptions.assumeTrue(NativeLib.available());

        final PatchBuilder.PreparedBaseline prepared = PaperCorrectionBaseline.prepare(
            new PatchBuilder(),
            view(0),
            null,
            false,
            null,
            WorldPreset.DEFAULT,
            "1.21.1",
            1234L,
            DimensionId.OVERWORLD
        );

        assertFalse(prepared.absolute());
        assertNotNull(prepared.baseline());
        assertNotNull(prepared.derived());
    }

    @Test
    void flatWorldUsesItsUniformResidualBaseline() {
        final PatchBuilder.PreparedBaseline prepared = PaperCorrectionBaseline.prepare(
            new PatchBuilder(),
            view(0),
            null,
            false,
            new FlatBaseline(1, 3, SurfaceKind.LAND.ordinal(), 1, 0),
            WorldPreset.FLAT,
            "1.21.1",
            1234L,
            DimensionId.OVERWORLD
        );

        assertFalse(prepared.absolute());
        assertNotNull(prepared.uniformPixel());
    }

    @Test
    void negotiatedMismatchAndCustomGeneratorsStayAbsolute() {
        final PatchBuilder builder = new PatchBuilder();
        assertTrue(PaperCorrectionBaseline.prepare(
            builder, view(0), null, true, null, WorldPreset.DEFAULT,
            "1.21.1", 1234L, DimensionId.OVERWORLD
        ).absolute());
        assertTrue(PaperCorrectionBaseline.prepare(
            builder, view(0), null, false, null, WorldPreset.CUSTOM,
            "1.21.1", 1234L, DimensionId.of("example", "custom")
        ).absolute());
    }

    @Test
    void vanillaNetherUsesTheRoofResidualBaseline() {
        NativeLib.init(temporary.resolve("confluxmap"));
        Assumptions.assumeTrue(NativeLib.available());

        final PatchBuilder.PreparedBaseline prepared = PaperCorrectionBaseline.prepare(
            new PatchBuilder(), view(0), null, false, null, WorldPreset.DEFAULT,
            "1.21.1", 1234L, DimensionId.NETHER
        );

        assertFalse(prepared.absolute());
        assertNotNull(prepared.baseline());
        assertEquals(PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID, prepared.mapColorId());
        assertTrue(java.util.Arrays.stream(prepared.baseline().terrainY).allMatch(
            y -> y == PredictionDimensions.NETHER_ROOF_Y
        ));
        for (final byte kind : prepared.derived().kind) {
            assertEquals(SurfaceKind.BEDROCK_CEILING.ordinal(), Byte.toUnsignedInt(kind));
        }

        final int index = 0;
        final SummaryCodec.Column naturalRoof = new SummaryCodec.Column(
            prepared.baseline().biomeId[index],
            prepared.derived().surfaceY[index],
            Byte.toUnsignedInt(prepared.derived().kind[index]),
            prepared.mapColorId(),
            prepared.derived().fluidDepth[index]
        );
        final PatchBuilder.Result result = new PatchBuilder().buildPrepared(
            view(0, naturalRoof), 0L, prepared
        );

        assertEquals(Proto.PATCH_MODE_RESIDUAL, result.mode());
        assertEquals(0, result.recordCount());
    }

    private static SummaryView view(final int lod) {
        return view(lod, null);
    }

    private static SummaryView view(final int lod, final SummaryCodec.Column column) {
        return new SummaryView() {
            @Override
            public int lod() {
                return lod;
            }

            @Override
            public long originBlockX() {
                return 0L;
            }

            @Override
            public long originBlockZ() {
                return 0L;
            }

            @Override
            public long revision() {
                return column == null ? 0L : 1L;
            }

            @Override
            public byte[] presence() {
                return new byte[Proto.PATCH_PRESENCE_BYTES];
            }

            @Override
            public Pixel pixel(final int pixelX, final int pixelZ) {
                return pixelX == 0 && pixelZ == 0 && column != null
                    ? new Pixel(true, 1L, column)
                    : null;
            }
        };
    }
}
