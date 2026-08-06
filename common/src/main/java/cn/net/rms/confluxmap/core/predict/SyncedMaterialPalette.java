package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-resource material samples keyed by server-synchronized block registry ids. */
public final class SyncedMaterialPalette {
    public enum Tint {
        NONE,
        GRASS,
        FOLIAGE,
        WATER,
        FIXED
    }

    public record Sample(
        int baseArgb,
        MaterialDetailProfile detail,
        Tint tint,
        int fixedTintArgb,
        int patternSalt
    ) {
        public Sample {
            if (detail == null || tint == null) {
                throw new IllegalArgumentException("synced material sample is incomplete");
            }
        }
    }

    private final Map<String, Sample> samples = new ConcurrentHashMap<>();

    public void put(final String materialId, final Sample sample) {
        if (materialId != null && !materialId.isEmpty() && sample != null) {
            samples.put(materialId, sample);
        }
    }

    public void clear() {
        samples.clear();
    }

    public boolean contains(final String materialId) {
        return materialId != null && samples.containsKey(materialId);
    }

    public int color(
        final String materialId,
        final int biomeId,
        final int fallback,
        final int worldX,
        final int worldZ,
        final PredictionPalette biomes
    ) {
        final Sample sample = materialId == null ? null : samples.get(materialId);
        if (sample == null) {
            return fallback;
        }
        final int tint = switch (sample.tint()) {
            case NONE -> 0xFFFFFFFF;
            case GRASS -> biomes.grassTint(biomeId);
            case FOLIAGE -> biomes.foliageTint(biomeId);
            case WATER -> biomes.waterTint(biomeId);
            case FIXED -> sample.fixedTintArgb();
        };
        final int tinted = Argb.multiply(sample.baseArgb(), tint);
        return sample.detail().apply(tinted, worldX, worldZ, sample.patternSalt());
    }
}
