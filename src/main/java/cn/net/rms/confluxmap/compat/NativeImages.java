package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.core.util.Argb;
import net.minecraft.client.texture.NativeImage;

/** Normalizes {@link NativeImage} pixel access to the core's ARGB representation. */
public final class NativeImages {
    private NativeImages() {
    }

    public static int getArgb(final NativeImage image, final int x, final int y) {
        //#if MC>=12103
        //$$ return image.getColorArgb(x, y);
        //#else
        return Argb.toAbgr(image.getColor(x, y));
        //#endif
    }

    public static void setArgb(final NativeImage image, final int x, final int y, final int argb) {
        //#if MC>=12103
        //$$ image.setColorArgb(x, y, argb);
        //#else
        image.setColor(x, y, Argb.toAbgr(argb));
        //#endif
    }
}
