package cn.net.rms.confluxmap.compat;

import net.minecraft.util.Identifier;

/**
 * The one place that knows how this Minecraft version builds an {@link Identifier}.
 *
 * <p>1.21 made the {@code Identifier} constructors private and moved construction to static
 * factories. Same reasoning as {@link Texts}: one seam here beats a preprocessor branch at each
 * of the mod's channel, texture and registry-key call sites.
 */
public final class Ids {
    private Ids() {
    }

    /** Parses a full {@code namespace:path} identifier, defaulting the namespace to minecraft. */
    public static Identifier of(final String id) {
        //#if MC>=12100
        //$$ return Identifier.of(id);
        //#else
        return new Identifier(id);
        //#endif
    }

    /** Builds an identifier from an explicit namespace and path. */
    public static Identifier of(final String namespace, final String path) {
        //#if MC>=12100
        //$$ return Identifier.of(namespace, path);
        //#else
        return new Identifier(namespace, path);
        //#endif
    }
}
