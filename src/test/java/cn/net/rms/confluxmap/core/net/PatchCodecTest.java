package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PatchCodecTest {
    @Test
    void roundTripUsesCoordinatesNotIterationOrder() throws Exception {
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(
            new PatchCodec.Sample(15 * 256 + 240, 4, 71, 2, 6, 8),
            new PatchCodec.Sample(0, 1, 64, 1, 1, 0),
            new PatchCodec.Sample(16 * 256 + 16, 2, 65, 2, 255, 2)
        ));
        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(patch));
        assertEquals(patch.samples().size(), decoded.samples().size());
        assertEquals(patch.samples().get(0), decoded.sampleAt(patch.samples().get(0).pixelIndex()));
        assertEquals(patch.samples().get(1), decoded.sampleAt(patch.samples().get(1).pixelIndex()));
        assertEquals(patch.samples().get(2), decoded.sampleAt(patch.samples().get(2).pixelIndex()));
    }

    @Test
    void malformedBodyIsRejected() {
        assertThrows(ProtoException.class, () -> PatchCodec.decode(new byte[] {1, 2, 3}));
    }
}
