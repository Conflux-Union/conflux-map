package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.mc.world.DimensionLayerPolicy.DimensionKind;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import net.minecraft.client.MinecraftClient;
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

    /** The layer to capture/display this tick, and the pivot Y its floor scan (if any) should use. */
    public record Decision(MapLayer layer, int pivotY) {
    }

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final boolean multiCore;

    private int debouncedPivotY;
    private int ticksSinceRefresh;
    private volatile Decision current = new Decision(MapLayer.SURFACE, 0);

    public LayerSelector(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.multiCore = Runtime.getRuntime().availableProcessors() > 1;
    }

    /** Main thread, from {@code ChunkCaptureService.onSessionChanged}: reset debounce state for the new session. */
    public void onSessionChanged(final SessionGuard.Session session) {
        ticksSinceRefresh = 0;
        debouncedPivotY = session.active()
            ? gameBridge.viewpoint().map(view -> (int) Math.floor(view.eyeY())).orElse(0)
            : 0;
        current = new Decision(MapLayer.SURFACE, 0);
    }

    /** Main thread, once per client tick. Returns (and publishes for {@link #current()}) the fresh decision. */
    public Decision tick() {
        final ClientWorld world = client.world;
        final PlayerView viewpoint = gameBridge.viewpoint().orElse(null);
        if (world == null || viewpoint == null) {
            current = new Decision(MapLayer.SURFACE, 0);
            return current;
        }

        final int eyeY = (int) Math.floor(viewpoint.eyeY());
        refreshPivot(eyeY);

        final DimensionKind kind = DimensionLayerPolicy.classify(
            DimensionLayerPolicy.dimensionId(world),
            world.getDimension()
        );
        final MapLayer layer;
        switch (kind) {
            case HAS_CEILING:
                layer = resolveNether(
                    config.layerOverride,
                    eyeY,
                    logicalHeight(world.getDimension()),
                    config.netherSliceY
                );
                break;
            case NO_SKY_NO_CEILING:
                layer = DimensionLayerPolicy.layerFor(kind);
                break;
            default:
                layer = resolveOverworld(world, viewpoint, eyeY, config.layerOverride);
        }

        final Decision decision = new Decision(layer, pivotFor(layer, world.getTopY(), debouncedPivotY));
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
        final DimensionKind kind = world != null
            ? DimensionLayerPolicy.classify(
                DimensionLayerPolicy.dimensionId(world), world.getDimension())
            : DimensionKind.SKY_LIT;
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

    /** §1 Case A: above-roof play uses the top-down roof layer; lower play keeps the current-Y floor scan. */
    static MapLayer resolveNether(
        final ConfluxConfig.LayerOverride override,
        final int eyeY,
        final int logicalHeight,
        final int sliceY
    ) {
        if (override == ConfluxConfig.LayerOverride.FORCE_SLICE) {
            return MapLayer.netherSlice(sliceY);
        }
        return override == ConfluxConfig.LayerOverride.FORCE_UNDERGROUND || eyeY >= logicalHeight
            ? MapLayer.NETHER_CEILING
            : MapLayer.NETHER_CURRENT;
    }

    private static int logicalHeight(final DimensionType type) {
        //#if MC>=12000
        //$$ return type.logicalHeight();
        //#else
        return type.getLogicalHeight();
        //#endif
    }

    /** §1 Case C: sky-light-gated automatic cave detection, or a manual pin. */
    private MapLayer resolveOverworld(
        final ClientWorld world, final PlayerView viewpoint, final int eyeY, final ConfluxConfig.LayerOverride override
    ) {
        if (override == ConfluxConfig.LayerOverride.FORCE_SLICE) {
            return MapLayer.caveSlice(config.caveSliceY);
        }
        if (override == ConfluxConfig.LayerOverride.FORCE_SURFACE) {
            return MapLayer.SURFACE;
        }
        if (override == ConfluxConfig.LayerOverride.FORCE_UNDERGROUND) {
            return MapLayer.CAVE_AUTO;
        }
        final BlockPos pos = new BlockPos(viewpoint.blockX(), eyeY, viewpoint.blockZ());
        //#if MC>=260100
        //$$ return world.getBrightness(LightLayer.SKY, pos) <= 0 ? MapLayer.CAVE_AUTO : MapLayer.SURFACE;
        //#else
        return world.getLightLevel(LightType.SKY, pos) <= 0 ? MapLayer.CAVE_AUTO : MapLayer.SURFACE;
        //#endif
    }

    /**
     * The pivot Y {@link cn.net.rms.confluxmap.mc.snapshot.McChunkSnapshotFactory} should scan
     * around for {@code layer}: the §2.2-debounced viewpoint Y for the player-relative layers, a
     * slice's configured fixed Y, the world's build-limit
     * for the nether-roof pivot, or an unused constant for the two top-down surface layers (kept
     * fixed so drifting player Y never spuriously flags a "layer changed" reseed while surfaced).
     */
    static int pivotFor(final MapLayer layer, final int worldTopY, final int debouncedPivotY) {
        switch (layer.type()) {
            case SURFACE:
            case END_SURFACE:
                return 0;
            case NETHER_CEILING:
                return worldTopY - 1;
            case CAVE_SLICE:
            case NETHER_SLICE:
                return layer.param();
            default:
                return debouncedPivotY;
        }
    }

    /** Deliverable A's cycle, with each dimension only offering the states meaningful to it. */
    public static ConfluxConfig.LayerOverride nextOverride(
        final DimensionKind kind,
        final ConfluxConfig.LayerOverride current
    ) {
        if (kind == DimensionKind.HAS_CEILING) {
            // Nether: current/automatic -> top of roof -> configured below-roof slice.
            return switch (current) {
                case AUTO -> ConfluxConfig.LayerOverride.FORCE_UNDERGROUND;
                case FORCE_UNDERGROUND -> ConfluxConfig.LayerOverride.FORCE_SLICE;
                default -> ConfluxConfig.LayerOverride.AUTO;
            };
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
            case FORCE_UNDERGROUND:
                return ConfluxConfig.LayerOverride.FORCE_SLICE;
            default:
                return ConfluxConfig.LayerOverride.AUTO;
        }
    }
}
