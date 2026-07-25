package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

/**
 * Session listener that samples every biome the client's live registry knows about (grass/
 * foliage/water tint at a fixed reference point, per surface-color-sampling.md's own "live"
 * sampling APIs) once per session, on the main thread, and publishes the result into {@link
 * PredictionState} as a fresh {@link PredictionPalette}. A biome id {@link CubiomesBiomeIds}
 * can't resolve (a registry path cubiomes has no matching id for - modded biomes, mainly) simply
 * has no entry and keeps using {@code core.predict.BiomeTable}'s fallback forever, per {@link
 * PredictionPalette}'s own per-id lookup contract. Palette data only ever affects color, never
 * which pixels are baseline water/land/foliage.
 */
public final class PredictionPaletteBuilder {
    private final MinecraftClient client;
    private final PredictionState state;

    public PredictionPaletteBuilder(final MinecraftClient client, final PredictionState state) {
        this.client = client;
        this.state = state;
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final ClientWorld world = client.world;
        if (!session.active() || world == null) {
            state.setPalette(PredictionPalette.defaults());
            return;
        }
        final Map<Integer, int[]> sampled = new HashMap<>();
        final var registry = Regs.biomes(world);
        for (final Identifier id : registry.getIds()) {
            final OptionalInt cubiomesId = CubiomesBiomeIds.idForName(id.getPath());
            if (cubiomesId.isEmpty()) {
                continue;
            }
            final Biome biome = Regs.biome(registry, id);
            if (biome == null) {
                continue;
            }
            final int grass = 0xFF000000 | biome.getGrassColorAt(0.0, 0.0);
            final int foliage = 0xFF000000 | biome.getFoliageColor();
            final int water = 0xFF000000 | biome.getWaterColor();
            sampled.put(cubiomesId.getAsInt(), new int[] {grass, foliage, water});
        }
        state.setPalette(PredictionPalette.fromSamples(sampled));
    }
}
