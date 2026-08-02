package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.util.Argb;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MaterialDetailProfileTest {
    @Test
    void textureLuminanceAddsBoundedDeterministicDetailWithoutMovingTheMean() {
        final int[] cells = {
            92, 108, 94, 106,
            96, 104, 98, 102,
            102, 98, 104, 96,
            106, 94, 108, 92
        };
        final MaterialDetailProfile profile = MaterialDetailProfile.fromLuminance(cells, 0.08);
        final int base = Argb.pack(123, 100, 140, 180);
        final Set<Integer> reds = new HashSet<>();
        long redSum = 0;
        int count = 0;
        for (int z = -32; z < 32; z++) {
            for (int x = -32; x < 32; x++) {
                final int detailed = profile.apply(base, x, z, 17);
                assertEquals(123, Argb.alpha(detailed));
                assertTrue(Argb.red(detailed) >= 92 && Argb.red(detailed) <= 108);
                assertEquals(detailed, profile.apply(base, x, z, 17), "the world-space pattern must be stable");
                reds.add(Argb.red(detailed));
                redSum += Argb.red(detailed);
                count++;
            }
        }

        assertTrue(reds.size() > 2, "a non-flat resource texture should produce visible material variation");
        assertEquals(100.0, redSum / (double) count, 0.5, "detail must preserve the sampled material colour on average");
    }

    @Test
    void flatAndTransparentColoursRemainUnchanged() {
        final MaterialDetailProfile flat = MaterialDetailProfile.fromLuminance(
            new int[] {80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80},
            0.08
        );
        assertEquals(0xFF326496, flat.apply(0xFF326496, 10, -20, 4));
        assertEquals(Argb.TRANSPARENT, flat.apply(Argb.TRANSPARENT, 10, -20, 4));
    }
}
