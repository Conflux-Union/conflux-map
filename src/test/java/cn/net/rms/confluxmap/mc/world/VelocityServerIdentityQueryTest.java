package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
        assertFalse(observe(timedOut, Texts.literal("您已连接至 survival。"))
            .consumed());
    }

    @Test
    void rearmingForAProxySwitchCannotInheritTheDepartedProfile() {
        final VelocityServerIdentityQuery query = new VelocityServerIdentityQuery(40);
        query.arm(true);
        query.arm(false);

        assertTrue(query.shouldAwait(100L, true, () -> { }));
        assertFalse(query.accept("creative").orElseThrow().mayAdoptLegacyProfile());
    }

    @Test
    void consumesOnlyARecognizedCurrentServerNoticeBeforeTheServerList() {
        final VelocityServerIdentityQuery query = new VelocityServerIdentityQuery(40);
        query.arm(false);
        assertFalse(observe(query, Texts.literal("您已连接至 survival。"))
            .consumed());
        assertTrue(query.shouldAwait(100L, true, () -> { }));

        assertFalse(observe(query, Texts.literal("<Player> ordinary chat")).consumed());
        final MutableText motd = Texts.literal("Welcome").formatted(Formatting.YELLOW)
            .append(Texts.literal("lobby").formatted(Formatting.GREEN))
            .append(Texts.literal(", ").formatted(Formatting.GRAY))
            .append(Texts.literal("creative").setStyle(Style.EMPTY
                .withColor(Formatting.GRAY)
                .withClickEvent(Texts.runCommand("/server creative"))));
        assertFalse(observe(query, motd).consumed());

        final VelocityServerIdentityQuery.Response notice = observe(
            query, Texts.literal("您已连接至 survival。")
        );
        assertTrue(notice.consumed());
        assertTrue(notice.match().isEmpty());

        final MutableText list = Texts.literal("可用的服务器：")
            .formatted(Formatting.YELLOW)
            .append(Texts.literal("survival").formatted(Formatting.GREEN))
            .append(Texts.literal(", ").formatted(Formatting.GRAY))
            .append(Texts.literal("creative").setStyle(Style.EMPTY
                .withColor(Formatting.GRAY)
                .withClickEvent(Texts.runCommand("/server creative"))));
        final VelocityServerIdentityQuery.Response servers = observe(query, list);
        assertTrue(servers.consumed());
        assertEquals("survival", servers.match().orElseThrow().serverName());

        assertFalse(observe(query, Texts.literal("您已连接至 survival。"))
            .consumed());
    }

    private static VelocityServerIdentityQuery.Response observe(
        final VelocityServerIdentityQuery query,
        final Text message
    ) {
        return query.observe(
            VelocityServerTextParser.parse(message),
            VelocityServerTextParser.isCurrentServerNotice(message)
        );
    }
}
