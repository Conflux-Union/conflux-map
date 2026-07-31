package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
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
            0
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
            -1
        );

        assertFalse(prepared.absolute());
        assertNotNull(prepared.uniformPixel());
    }

    @Test
    void negotiatedMismatchAndCustomGeneratorsStayAbsolute() {
        final PatchBuilder builder = new PatchBuilder();
        assertTrue(PaperCorrectionBaseline.prepare(
            builder, view(0), null, true, null, WorldPreset.DEFAULT,
            "1.21.1", 1234L, 0
        ).absolute());
        assertTrue(PaperCorrectionBaseline.prepare(
            builder, view(0), null, false, null, WorldPreset.CUSTOM,
            "1.21.1", 1234L, -1
        ).absolute());
    }

    private static SummaryView view(final int lod) {
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
                return 0L;
            }

            @Override
            public byte[] presence() {
                return new byte[Proto.PATCH_PRESENCE_BYTES];
            }

            @Override
            public Pixel pixel(final int pixelX, final int pixelZ) {
                return null;
            }
        };
    }
}
