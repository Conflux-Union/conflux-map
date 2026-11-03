package cn.net.rms.confluxmap.mc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.net.rms.confluxmap.api.ActionApi.TeleportResult;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.FeatureRestrictionsS2C;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FabricActionApiTest {
    @Test
    void queuedTeleportChecksTheCurrentServerRestrictionBeforeAccessingWaypoints() {
        final SessionGuard guard = new SessionGuard();
        guard.begin(new WorldIdentity("local", "world"), DimensionId.OVERWORLD);
        final CompanionSession companion = new CompanionSession();
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final GameBridge bridge = new GameBridge() {
            @Override
            public SessionGuard.Session session() {
                return guard.current();
            }

            @Override
            public Optional<PlayerView> player(final float tickDelta) {
                return Optional.empty();
            }

            @Override
            public boolean isCameraDetached() {
                return false;
            }

            @Override
            public boolean isOnRenderThread() {
                return false;
            }

            @Override
            public void runOnRenderThread(final Runnable task) {
                tasks.add(task);
            }
        };
        final FabricActionApi actions = new FabricActionApi(
            null, bridge, null, null, null, null, null, null, guard, companion
        );
        final var result = actions.requestTeleport(UUID.randomUUID());
        assertFalse(result.isDone());
        companion.onHelloSent();
        companion.onFeatureRestrictions(new FeatureRestrictionsS2C(false, false, true, false));
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, false, false, false, false, false, false, false),
            "11111111-2222-3333-4444-555555555555", "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 4), List.of()
        ));
        tasks.remove().run();
        assertEquals(TeleportResult.FORBIDDEN_BY_SERVER, result.join());
    }
}
