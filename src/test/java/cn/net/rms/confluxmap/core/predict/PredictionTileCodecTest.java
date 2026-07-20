package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionTileCodecTest {
    @Test
    void olderCorrectionsAreRejectedWhenBaselineSemanticsChange() throws Exception {
        final PredictionTileCodec.FileData data = new PredictionTileCodec.FileData(
            2, -1, -1, 10L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of())
        );
        final byte[] encoded = PredictionTileCodec.encode(data);
        final PredictionTileCodec.FileData decoded = PredictionTileCodec.decode(encoded);
        assertEquals(data.lod(), decoded.lod());
        assertEquals(data.tileX(), decoded.tileX());
        assertEquals(data.tileZ(), decoded.tileZ());
        assertEquals(data.revision(), decoded.revision());
        assertArrayEquals(data.presence(), decoded.presence());
        assertEquals(0, decoded.patch().size());
        encoded[4] = (byte) (PredictionTileCodec.FORMAT_VERSION - 1);

        assertThrows(ProtoException.class, () -> PredictionTileCodec.decode(encoded));
    }
}
