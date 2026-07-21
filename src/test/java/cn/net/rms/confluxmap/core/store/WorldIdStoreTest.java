package cn.net.rms.confluxmap.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldIdStoreTest {
    @Test
    void parseAcceptsCanonicalForm() {
        final UUID source = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals(source, WorldIdStore.parse("{\"uuid\":\"" + source + "\"}"));
    }

    @Test
    void parseRejectsMissingField() {
        assertEquals(null, WorldIdStore.parse("{\"notUuid\":\"x\"}"));
    }

    @Test
    void parseRejectsMalformedUuid() {
        assertEquals(null, WorldIdStore.parse("{\"uuid\":\"not-a-uuid\"}"));
    }

    @Test
    void parseRejectsBlankAndNull() {
        assertEquals(null, WorldIdStore.parse(null));
        assertEquals(null, WorldIdStore.parse(""));
        assertEquals(null, WorldIdStore.parse("   "));
    }
}
