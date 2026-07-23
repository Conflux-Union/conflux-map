package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.compat.Texts;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;

/**
 * Watches for the local player's death screen appearing (edge-triggered:
 * fires once on the transition into that screen, never re-fires while it
 * stays open) and records a DEATH-type waypoint at the death position, per
 * {@code waypoint-ux.md} S4.
 *
 * <p>Kept simple per the implementation brief rather than replicating the
 * reference's three-mode/label-rename-chain retention scheme: one config
 * int ({@link ConfluxConfig#deathPointsKept}) caps how many death points are
 * kept *per dimension*, oldest pruned first; 0 disables creating new ones
 * entirely. Pruning is scoped per-dimension deliberately - see
 * {@code waypoint-ux.md} S4's confidence note on why the reference
 * implementation's whole-collection scan is a quirk worth not repeating.
 */
public final class DeathWatcher {
    /** Starts near-white, matching the reference's death-point color convention. */
    private static final int DEATH_COLOR = 0xFFE8E8E8;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final GameBridge gameBridge;
    private final ConfluxConfig config;
    private final WaypointService waypoints;

    private boolean deathScreenWasOpen;

    public DeathWatcher(final GameBridge gameBridge, final ConfluxConfig config, final WaypointService waypoints) {
        this.gameBridge = gameBridge;
        this.config = config;
        this.waypoints = waypoints;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(final MinecraftClient client) {
        final boolean deathScreenOpen = client.currentScreen instanceof DeathScreen;
        final boolean justDied = deathScreenOpen && !deathScreenWasOpen;
        deathScreenWasOpen = deathScreenOpen;
        if (justDied) {
            onDeath();
        }
    }

    private void onDeath() {
        if (config.deathPointsKept <= 0) {
            return;
        }
        final WaypointStore store = waypoints.current();
        final Optional<PlayerView> playerView = gameBridge.player();
        if (store == null || playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final String name = Texts.translatable("confluxmap.waypoint.death.name").getString()
            + " " + LocalTime.now().format(TIME_FORMAT);
        final Waypoint death = Waypoint.create(
            name, player.dimension(), player.blockX(), player.blockY(), player.blockZ(),
            DEATH_COLOR, "", Waypoint.Type.DEATH
        );
        store.add(death);
        prune(store, player.dimension());
    }

    /** Keeps the newest {@link ConfluxConfig#deathPointsKept} death points in {@code dimension}, deleting older ones. */
    private void prune(final WaypointStore store, final DimensionId dimension) {
        final List<Waypoint> deaths = new ArrayList<>();
        for (final Waypoint waypoint : store.list()) {
            if (waypoint.type == Waypoint.Type.DEATH && waypoint.dimensionId.equals(dimension)) {
                deaths.add(waypoint);
            }
        }
        final int excess = deaths.size() - config.deathPointsKept;
        if (excess <= 0) {
            return;
        }
        deaths.sort((a, b) -> Long.compare(a.createdAtEpochMs, b.createdAtEpochMs));
        for (int i = 0; i < excess; i++) {
            store.remove(deaths.get(i).id);
        }
    }
}
