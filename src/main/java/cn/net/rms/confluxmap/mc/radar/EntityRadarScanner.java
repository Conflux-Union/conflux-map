package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarFilter;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Periodically classifies the client's already-loaded entities into a {@link RadarEntry}
 * snapshot for {@code MinimapHudRenderer} to draw. Reads only {@link MinecraftClient#world}'s
 * standard renderable-entity collection ({@code ClientWorld#getEntities()}) — the exact same
 * pool the game itself iterates to draw entities in the 3D world, matching the anti-cheat
 * boundary from the implementation plan (no packets, no server queries).
 *
 * <p>Client thread only: registered on {@code ClientTickEvents.END_CLIENT_TICK}, and its
 * snapshot is read from the render callback on the same (client) thread, so no explicit
 * synchronization is needed beyond keeping the published list immutable.
 *
 * <p><b>Classification vs. spec.</b> {@code docs/reference-specs/radar-icons.md} section 1
 * describes a live per-instance classifier (a "Monster" marker-interface check, a
 * killer-rabbit-variant special case, and an "Angerable" trait checked against the local
 * player's UUID) used for the actual radar, plus a separate coarser
 * {@link net.minecraft.entity.SpawnGroup}-based classifier used only for VoxelMap's
 * per-species management dialog. M1 has no per-species dialog and no icon system, so this
 * scanner uses the coarser spawn-group classifier for everything: {@link SpawnGroup#MONSTER}
 * to {@link RadarCategory#HOSTILE}; {@code CREATURE}/{@code AMBIENT}/water
 * variants/{@code MISC} to {@link RadarCategory#PASSIVE}; anything else living to
 * {@link RadarCategory#OTHER} (unreachable for vanilla 1.17.1 entities). This is simpler and
 * cheaper than reproducing the live classifier, at the cost of not flagging a neutral mob that
 * is currently angry at the player as hostile the way the reference implementation does.
 */
public final class EntityRadarScanner {
    /** Full re-scan interval, in client ticks. Matches the reference spec's periodic-scan interval. */
    private static final int SCAN_INTERVAL_TICKS = 16;
    /**
     * Extra horizontal/vertical margin (world blocks) applied only at scan time, so an entity
     * is captured into the tracked list slightly before it visually crosses the render-time
     * cull boundary in {@code MinimapHudRenderer} — avoids pop-in/out flicker between scans,
     * mirroring the spec's two-tier buffered-scan/unbuffered-refresh design.
     */
    private static final int SCAN_RANGE_BUFFER = 5;
    /** Vertical window (world blocks) for ordinary entities, per spec section 1. */
    private static final int VERTICAL_RANGE = 32;
    /** Doubled vertical window for the one flying-hostile exception the spec calls out (the Phantom). */
    private static final int PHANTOM_VERTICAL_RANGE = 64;

    private final MinecraftClient client;
    private final ConfluxConfig config;

    private volatile List<RadarEntry> snapshot = List.of();
    private int tickCounter;

    public EntityRadarScanner(final MinecraftClient client, final ConfluxConfig config) {
        this.client = client;
        this.config = config;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    /** Main thread, from the session tracker: drop every tracked entity on world/dimension change. */
    public void onSessionChanged(final SessionGuard.Session session) {
        snapshot = List.of();
        tickCounter = 0;
    }

    /** Render thread (== client thread here); immutable, safe to iterate without copying. */
    public List<RadarEntry> snapshot() {
        return snapshot;
    }

    private void tick() {
        if (!config.radarEnabled) {
            if (!snapshot.isEmpty()) {
                snapshot = List.of();
            }
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        snapshot = scan();
    }

    private List<RadarEntry> scan() {
        final PlayerEntity self = client.player;
        if (self == null || client.world == null) {
            return List.of();
        }
        final double px = self.getX();
        final double py = self.getY();
        final double pz = self.getZ();
        final long horizontalRangeSq = square(config.radarRange + SCAN_RANGE_BUFFER);

        final List<RadarEntry> raw = new ArrayList<>();
        for (final Entity entity : client.world.getEntities()) {
            if (entity == self || !(entity instanceof LivingEntity)) {
                continue;
            }
            if (entity.isSpectator() || entity.isInvisibleTo(self)) {
                continue;
            }
            if (entity instanceof PlayerEntity && entity.isSneaking()) {
                continue;
            }

            final double dx = entity.getX() - px;
            final double dz = entity.getZ() - pz;
            if (dx * dx + dz * dz > horizontalRangeSq) {
                continue;
            }
            final int yDelta = (int) Math.round(entity.getY() - py);
            final int verticalRange = (entity instanceof PhantomEntity ? PHANTOM_VERTICAL_RANGE : VERTICAL_RANGE) + SCAN_RANGE_BUFFER;
            if (Math.abs(yDelta) > verticalRange) {
                continue;
            }

            final RadarCategory category = classify(entity);
            final String name = category == RadarCategory.PLAYER ? entity.getName().getString() : null;
            raw.add(new RadarEntry(entity.getX(), entity.getZ(), yDelta, category, name, entity.getId()));
        }

        final RadarFilter filter = new RadarFilter(
            config.radarShowPlayers, config.radarShowHostile, config.radarShowPassive, config.radarShowOther, config.radarMaxEntities
        );
        return filter.apply(raw, px, pz);
    }

    private static RadarCategory classify(final Entity entity) {
        if (entity instanceof PlayerEntity) {
            return RadarCategory.PLAYER;
        }
        final SpawnGroup group = entity.getType().getSpawnGroup();
        if (group == SpawnGroup.MONSTER) {
            return RadarCategory.HOSTILE;
        }
        if (group == SpawnGroup.CREATURE || group == SpawnGroup.AMBIENT
            || group == SpawnGroup.WATER_CREATURE || group == SpawnGroup.WATER_AMBIENT
            || group == SpawnGroup.UNDERGROUND_WATER_CREATURE || group == SpawnGroup.MISC) {
            return RadarCategory.PASSIVE;
        }
        return RadarCategory.OTHER;
    }

    private static long square(final int v) {
        return (long) v * v;
    }
}
