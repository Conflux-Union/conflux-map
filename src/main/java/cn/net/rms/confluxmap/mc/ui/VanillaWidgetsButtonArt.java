//#if MC<12002
package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.util.HexCodec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.imageio.ImageIO;

/**
 * Pixel-level vanilla-button detection for the pre-1.20.2 widgets.png atlas.
 *
 * <p>Versions before 1.20.2 pack every widget into one atlas, so counting override
 * layers cannot tell a hotbar reskin from a button reskin. Vanilla draws buttons from
 * three 200x20 strips at v = 46/66/86 (disabled/normal/hover), so the effective atlas
 * still carries vanilla button art exactly when those pixels hash to the vanilla value.
 * The strip layout was read out of 1.17.1/1.18.2 {@code ClickableWidget} and 1.20.1
 * {@code PressableWidget} bytecode.
 */
final class VanillaWidgetsButtonArt {
    static final int REGION_X = 0;
    static final int REGION_Y = 46;
    static final int REGION_WIDTH = 200;
    static final int REGION_HEIGHT = 60;

    /**
     * True only when the stream decodes to an atlas whose button strips hash to
     * {@code expectedRegionDigest}. Undecodable or region-cropping atlases report false
     * so the caller falls back to the layer-count verdict.
     */
    static boolean matchesVanillaButtons(
        final InputStream widgetsPng,
        final String expectedRegionDigest
    ) throws IOException {
        final BufferedImage atlas = ImageIO.read(widgetsPng);
        if (atlas == null
            || atlas.getWidth() < REGION_X + REGION_WIDTH
            || atlas.getHeight() < REGION_Y + REGION_HEIGHT) {
            return false;
        }
        return expectedRegionDigest.equals(digestRegion(atlas));
    }

    private static String digestRegion(final BufferedImage atlas) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        for (int y = REGION_Y; y < REGION_Y + REGION_HEIGHT; y++) {
            for (int x = REGION_X; x < REGION_X + REGION_WIDTH; x++) {
                final int argb = atlas.getRGB(x, y);
                digest.update((byte) (argb >>> 24));
                digest.update((byte) (argb >>> 16));
                digest.update((byte) (argb >>> 8));
                digest.update((byte) argb);
            }
        }
        return HexCodec.format(digest.digest());
    }

    private VanillaWidgetsButtonArt() {
    }
}
//#endif
