package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.dimension.DimensionType;

/**
 * Decides the active capture+display {@link MapLayer} each tick, per
 * cave-nether-layers.md §1 (detection) and §2.2 (pivot-Y debounce). Pull-based:
 * {@link cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService#tick()} calls
 * {@link #tick()} once per client tick (before capturing), and the render
 * thread reads the published result back via {@link #current()}.
 *
 * <p>Manual override state ({@link ConfluxConfig#layerOverride}) is stored in
 * config rather than here, so it survives a restart; {@link #cycleOverride()}
 * is the keybind entry point that advances it.
 */
public final class LayerSelector {
    /** §2.2 pivot-Y refresh debounce thresholds. */
    private static final int Y_THRESHOLD_MULTI_CORE = 2;
    private static final int Y_THRESHOLD_SINGLE_CORE = 5;
    private static final int MAX_TICKS_MULTI_CORE = 300;
    private static final int MAX_TICKS_SINGLE_CORE = 3000;

    /** §1's three detection cases, generalized from "has_ceiling"/"has_sky_light" dimension metadata. */
    public enum DimensionKind {
        /** Case A: Nether-like (e.g. the Nether itself). */
        HAS_CEILING,
        /** Case B: no ceiling and no ambient sky light (e.g. the End). */
        NO_SKY_NO_CEILING,
        /** Case C: ordinary sky-lit dimension (e.g. the Overworld). */
        SKY_LIT
    }

    /** The layer to capture/display this tick, and the pivot Y its floor scan (if any) should use. */
    public record Decision(MapLayer layer, int pivotY) {
    }

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final boolean multiCore;

    private int debouncedPivotY;
    private int ticksSinceRefresh;
    private volatile Decision current = new Decision(MapLayer.SURFACE, 0);

    public LayerSelector(final MinecraftClient client, final ConfluxConfig config) {
        this.client = client;
        this.config = config;
        this.multiCore = Runtime.getRuntime().availableProcessors() > 1;
    }

    /** Main thread, from {@code ChunkCaptureService.onSessionChanged}: reset debounce state for the new session. */
    public void onSessionChanged(final SessionGuard.Session session) {
        ticksSinceRefresh = 0;
        final ClientPlayerEntity player = session.active() ? client.player : null;
        debouncedPivotY = player != null ? (int) Math.floor(player.getEyeY()) : 0;
        current = new Decision(MapLayer.SURFACE, 0);
    }

    /** Main thread, once per client tick. Returns (and publishes for {@link #current()}) the fresh decision. */
    public Decision tick() {
        final ClientWorld world = client.world;
        final ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            current = new Decision(MapLayer.SURFACE, 0);
            return current;
        }

        final int eyeY = (int) Math.floor(player.getEyeY());
        refreshPivot(eyeY);

        final DimensionKind kind = classify(world.getDimension());
        final MapLayer layer;
        switch (kind) {
            case HAS_CEILING:
                layer = resolveNether(config.layerOverride);
                break;
            case NO_SKY_NO_CEILING:
                // §1.2/§4: M1 always renders the End as a plain top-down surface (no player-relative
                // switching); there is no second End layer for the override cycle to reach either.
                layer = MapLayer.END_SURFACE;
                break;
            default:
                layer = resolveOverworld(world, player, eyeY, config.layerOverride);
        }

        final Decision decision = new Decision(layer, pivotFor(layer, world, debouncedPivotY));
        current = decision;
        return decision;
    }

    /** The most recently published decision; safe to read from the render thread. */
    public Decision current() {
        return current;
    }

    /** Keybind entry point ({@code key.confluxmap.cycle_layer}): advances the override for the current dimension. */
    public void cycleOverride() {
        final ClientWorld world = client.world;
        final DimensionKind kind = world != null ? classify(world.getDimension()) : DimensionKind.SKY_LIT;
        config.layerOverride = nextOverride(kind, config.layerOverride);
    }

    private void refreshPivot(final int eyeY) {
        ticksSinceRefresh++;
        final int threshold = multiCore ? Y_THRESHOLD_MULTI_CORE : Y_THRESHOLD_SINGLE_CORE;
        final int maxTicks = multiCore ? MAX_TICKS_MULTI_CORE : MAX_TICKS_SINGLE_CORE;
        if (Math.abs(eyeY - debouncedPivotY) >= threshold || ticksSinceRefresh >= maxTicks) {
            debouncedPivotY = eyeY;
            ticksSinceRefresh = 0;
        }
    }

    /** §1 Case A: nether-like dimensions never fall back to bare SURFACE; only AUTO/NETHER_CEILING exist. */
    private static MapLayer resolveNether(final ConfluxConfig.LayerOverride override) {
        return override == ConfluxConfig.LayerOverride.FORCE_UNDERGROUND ? MapLayer.NETHER_CEILING : MapLayer.NETHER_CURRENT;
    }

    /** §1 Case C: sky-light-gated automatic cave detection, or a manual pin. */
    private static MapLayer resolveOverworld(
        final ClientWorld world, final ClientPlayerEntity player, final int eyeY, final ConfluxConfig.LayerOverride override
    ) {
        if (override == ConfluxConfig.LayerOverride.FORCE_SURFACE) {
            return MapLayer.SURFACE;
        }
        if (override == ConfluxConfig.LayerOverride.FORCE_UNDERGROUND) {
            return MapLayer.CAVE_AUTO;
        }
        final BlockPos pos = new BlockPos(player.getBlockPos().getX(), eyeY, player.getBlockPos().getZ());
        return world.getLightLevel(LightType.SKY, pos) <= 0 ? MapLayer.CAVE_AUTO : MapLayer.SURFACE;
    }

    /**
     * The pivot Y {@link cn.net.rms.confluxmap.mc.snapshot.McChunkSnapshotFactory} should scan
     * around for {@code layer}: the §2.2-debounced player Y for the player-relative layers, a
     * slice's own fixed Y (deferred to a future UI slice, per the plan), the world's build-limit
     * for the nether-roof pivot, or an unused constant for the two top-down surface layers (kept
     * fixed so drifting player Y never spuriously flags a "layer changed" reseed while surfaced).
     */
    private static int pivotFor(final MapLayer layer, final ClientWorld world, final int debouncedPivotY) {
        switch (layer.type()) {
            case SURFACE:
            case END_SURFACE:
                return 0;
            case NETHER_CEILING:
                return world.getTopY() - 1;
            case CAVE_SLICE:
            case NETHER_SLICE:
                return layer.param();
            default:
                return debouncedPivotY;
        }
    }

    /** §1's generic classification: has_ceiling, else no-sky-light, else ordinary sky-lit. */
    public static DimensionKind classify(final DimensionType type) {
        if (type.hasCeiling()) {
            return DimensionKind.HAS_CEILING;
        }
        if (!type.hasSkyLight()) {
            return DimensionKind.NO_SKY_NO_CEILING;
        }
        return DimensionKind.SKY_LIT;
    }

    /** Deliverable A's cycle, with each dimension only offering the states meaningful to it. */
    private static ConfluxConfig.LayerOverride nextOverride(final DimensionKind kind, final ConfluxConfig.LayerOverride current) {
        if (kind == DimensionKind.HAS_CEILING) {
            // Nether: no "force surface" state (it would show the roof) - AUTO <-> NETHER_CEILING only.
            return current == ConfluxConfig.LayerOverride.AUTO
                ? ConfluxConfig.LayerOverride.FORCE_UNDERGROUND
                : ConfluxConfig.LayerOverride.AUTO;
        }
        if (kind == DimensionKind.NO_SKY_NO_CEILING) {
            // End: only one layer exists in M1, so cycling is a predictable no-op.
            return ConfluxConfig.LayerOverride.AUTO;
        }
        switch (current) {
            case AUTO:
                return ConfluxConfig.LayerOverride.FORCE_SURFACE;
            case FORCE_SURFACE:
                return ConfluxConfig.LayerOverride.FORCE_UNDERGROUND;
            default:
                return ConfluxConfig.LayerOverride.AUTO;
        }
    }
}
