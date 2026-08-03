package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VelocityServerIdentityQueryTest {
    @Test
    void sendsOnceAndWaitsForAResponseOnlyInsideTheProbeWindow() {
        final VelocityServerIdentityQuery query = new VelocityServerIdentityQuery(40);
        final AtomicInteger commands = new AtomicInteger();
        query.arm(false);

        assertTrue(query.shouldAwait(100L, true, commands::incrementAndGet));
        assertTrue(query.shouldAwait(120L, true, commands::incrementAndGet));
        assertEquals(1, commands.get());
        assertEquals("creative", query.accept("creative").orElseThrow().serverName());
        assertFalse(query.shouldAwait(121L, true, commands::incrementAndGet));
        assertTrue(query.accept("survival").isEmpty());
    }

    @Test
    void unsupportedServersAndTimeoutsReleaseTheConservativeFallback() {
        final VelocityServerIdentityQuery unsupported = new VelocityServerIdentityQuery(40);
        unsupported.arm(true);
        assertFalse(unsupported.shouldAwait(100L, false, () -> { }));

        final VelocityServerIdentityQuery timedOut = new VelocityServerIdentityQuery(40);
        timedOut.arm(true);
        assertTrue(timedOut.shouldAwait(100L, true, () -> { }));
        assertFalse(timedOut.shouldAwait(140L, true, () -> { }));
        assertTrue(timedOut.accept("survival").isEmpty());
    }

    @Test
    void rearmingForAProxySwitchCannotInheritTheDepartedProfile() {
        final VelocityServerIdentityQuery query = new VelocityServerIdentityQuery(40);
        query.arm(true);
        query.arm(false);

        assertTrue(query.shouldAwait(100L, true, () -> { }));
        assertFalse(query.accept("creative").orElseThrow().mayAdoptLegacyProfile());
    }
}
