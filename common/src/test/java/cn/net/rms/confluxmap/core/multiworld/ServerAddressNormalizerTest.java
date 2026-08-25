package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerAddressNormalizerTest {
    @Test
    void certainlyEquivalentSpellingsCollapse() {
        assertEquals("mc.example.com", ServerAddressNormalizer.normalize("MC.Example.com"));
        assertEquals("mc.example.com", ServerAddressNormalizer.normalize("mc.example.com."));
        assertEquals("mc.example.com", ServerAddressNormalizer.normalize("  mc.example.com  "));
        assertEquals("mc.example.com", ServerAddressNormalizer.normalize("mc.example.com..."));
    }

    /**
     * An explicit port suppresses the client's {@code _minecraft._tcp} SRV lookup, so the two
     * spellings can reach different machines. Merging them is not recoverable; staying split is.
     */
    @Test
    void anExplicitDefaultPortIsNotAssumedToBeTheSameEndpoint() {
        assertEquals("mc.example.com:25565", ServerAddressNormalizer.normalize("MC.Example.com:25565"));
        assertEquals("mc.example.com:25565", ServerAddressNormalizer.normalize("mc.example.com.:25565"));
    }

    @Test
    void otherPortsAreKeptVerbatim() {
        assertEquals("mc.example.com:25566", ServerAddressNormalizer.normalize("MC.Example.com:25566"));
        assertEquals("mc.example.com:abc", ServerAddressNormalizer.normalize("mc.example.com:abc"));
        assertEquals("mc.example.com:", ServerAddressNormalizer.normalize("mc.example.com.:"));
    }

    @Test
    void bareIpv6LiteralIsNotSplitAtItsColons() {
        assertEquals("::1", ServerAddressNormalizer.normalize("::1"));
        assertEquals("2001:db8::1", ServerAddressNormalizer.normalize("2001:DB8::1"));
    }

    @Test
    void bracketedIpv6KeepsItsPort() {
        assertEquals("[::1]", ServerAddressNormalizer.normalize("[::1]"));
        assertEquals("[::1]:25565", ServerAddressNormalizer.normalize("[::1]:25565"));
        assertEquals("[::1]:25566", ServerAddressNormalizer.normalize("[::1]:25566"));
    }

    @Test
    void ipv4LiteralKeepsItsShape() {
        assertEquals("192.0.2.10", ServerAddressNormalizer.normalize("192.0.2.10"));
        assertEquals("192.0.2.10:25565", ServerAddressNormalizer.normalize("192.0.2.10:25565"));
    }

    @Test
    void degenerateInputDoesNotThrow() {
        assertEquals("", ServerAddressNormalizer.normalize(null));
        assertEquals("", ServerAddressNormalizer.normalize("   "));
        assertEquals(":25565", ServerAddressNormalizer.normalize(":25565"));
        assertEquals("...", ServerAddressNormalizer.normalize("..."));
    }
}
