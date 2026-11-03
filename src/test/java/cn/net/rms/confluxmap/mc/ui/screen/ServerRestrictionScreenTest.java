package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.net.FeatureRestrictionsS2C;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

class ServerRestrictionScreenTest {
    @Test
    void mapRequestsAreReplacedWhileWaypointsAndClosingRemainAvailable() throws Exception {
        final Field minecraftInstance = MinecraftClient.class.getDeclaredField("instance");
        minecraftInstance.setAccessible(true);
        final Object previous = minecraftInstance.get(null);
        minecraftInstance.set(null, allocate(MinecraftClient.class));
        try {
            final CompanionSession session = new CompanionSession();
            final Screen map = allocate(FullscreenMapScreen.class);
            final Screen waypoints = allocate(WaypointListScreen.class);
            assertSame(map, ServerRestrictionScreen.resolve(map, session));

            session.onHelloSent();
            session.onFeatureRestrictions(new FeatureRestrictionsS2C(true, true, false, false, "Server rules"));
            session.onPolicy(new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, false, false, false, false, false, false, false),
                "11111111-2222-3333-4444-555555555555", "1.17.1",
                new HelloPolicyS2C.Budgets(65_536, 8, 300, 4), List.of()
            ));
            final ServerRestrictionScreen restricted = assertInstanceOf(
                ServerRestrictionScreen.class, ServerRestrictionScreen.resolve(map, session)
            );
            assertEquals("Server rules", message(restricted).getString());
            assertSame(waypoints, ServerRestrictionScreen.resolve(waypoints, session));
            assertNull(ServerRestrictionScreen.resolve(null, session));
            assertEquals(
                Texts.translatable("confluxmap.screen.restriction.message").getString(),
                message(new ServerRestrictionScreen(" ")).getString()
            );
            session.onFeatureRestrictions(new FeatureRestrictionsS2C(true, false, false, false));
            assertSame(map, ServerRestrictionScreen.resolve(map, session));
            session.reset();
            assertSame(map, ServerRestrictionScreen.resolve(map, session));
        } finally {
            minecraftInstance.set(null, previous);
        }
    }

    private static Text message(final ServerRestrictionScreen screen) throws Exception {
        final Field field = ServerRestrictionScreen.class.getDeclaredField("message");
        field.setAccessible(true);
        return (Text) field.get(screen);
    }

    private static <T> T allocate(final Class<T> type) throws Exception {
        final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type));
    }
}
