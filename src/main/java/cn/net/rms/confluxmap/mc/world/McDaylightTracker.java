package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.tile.TileService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;

/**
 * Drives {@link DaylightModel} once per client tick from the live world's sky angle, using
 * vanilla's own sky-brightness cosine curve (the same one {@code BackgroundRenderer} uses for
 * fog/sky color): {@code f = clamp(2*cos(skyAngle * 2*pi) + 0.5, 0, 1)}, read here via {@link
 * ClientWorld#getSkyAngleRadians} which already returns {@code skyAngle * 2*pi}.
 *
 * <p>{@link ConfluxConfig#dynamicLighting} off, no world loaded, or a dimension with no sky
 * light (Nether/End - fixed brightness, and neither ever displays the SURFACE layer this
 * feeds anyway) all pin the factor to 1.0, i.e. today's undarkened rendering. Whenever {@link
 * DaylightModel#update} reports the quantized bucket moved - including the one-time jump back
 * to 1.0 when the setting or dimension makes the factor pin again - the currently active
 * world's SURFACE tiles are relit via {@link TileService#markSurfaceRelit}, so toggling the
 * setting off promptly clears any existing night-darkening instead of leaving it stale.
 */
public final class McDaylightTracker {
    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final DaylightModel model;
    private final MapWorldService mapWorlds;
    private final TileService tiles;

    public McDaylightTracker(
        final MinecraftClient client,
        final ConfluxConfig config,
        final DaylightModel model,
        final MapWorldService mapWorlds,
        final TileService tiles
    ) {
        this.client = client;
        this.config = config;
        this.model = model;
        this.mapWorlds = mapWorlds;
        this.tiles = tiles;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    private void tick() {
        final boolean changed = model.update(computeFactor());
        if (!changed) {
            return;
        }
        final MapWorld world = mapWorlds.current();
        if (world != null) {
            tiles.markSurfaceRelit(world.session().token());
        }
    }

    private float computeFactor() {
        final ClientWorld world = client.world;
        if (!config.dynamicLighting || world == null || !world.getDimension().hasSkyLight()) {
            return 1f;
        }
        final float raw = MathHelper.cos(world.getSkyAngleRadians(1.0f)) * 2.0f + 0.5f;
        return MathHelper.clamp(raw, 0f, 1f);
    }
}
