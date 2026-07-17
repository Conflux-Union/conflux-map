package cn.net.rms.confluxmap.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A dimension identified by its resource id string (e.g. "minecraft:overworld").
 * Numeric legacy ids (0/-1/1) are never used anywhere in this mod.
 */
public final class DimensionId {
    public static final DimensionId OVERWORLD = new DimensionId("minecraft", "overworld");
    public static final DimensionId NETHER = new DimensionId("minecraft", "the_nether");
    public static final DimensionId END = new DimensionId("minecraft", "the_end");

    private final String namespace;
    private final String path;

    private DimensionId(final String namespace, final String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static DimensionId of(final String namespace, final String path) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.equals("minecraft")) {
            switch (path) {
                case "overworld": return OVERWORLD;
                case "the_nether": return NETHER;
                case "the_end": return END;
                default: break;
            }
        }
        return new DimensionId(namespace, path);
    }

    public static DimensionId parse(final String id) {
        final int colon = id.indexOf(':');
        if (colon < 0) {
            return of("minecraft", id);
        }
        return of(id.substring(0, colon), id.substring(colon + 1));
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    /** Filesystem-safe directory name, stable across sessions. */
    public String fileName() {
        return sanitize(namespace) + "_" + sanitize(path);
    }

    private static String sanitize(final String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DimensionId)) {
            return false;
        }
        final DimensionId other = (DimensionId) o;
        return namespace.equals(other.namespace) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return namespace.hashCode() * 31 + path.hashCode();
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
