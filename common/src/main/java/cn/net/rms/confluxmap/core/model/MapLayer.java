package cn.net.rms.confluxmap.core.model;

/**
 * Which vertical view of the world a tile shows. {@code param} is the Y value for
 * slice-style layers and 0 otherwise. {@code cacheId} is stable and used in tile keys
 * and cache paths; parameterized layers embed the parameter so bands never mix.
 */
public record MapLayer(Type type, int param) {
    public enum Type {
        SURFACE("surface", true),
        CAVE_AUTO("cave", false),
        CAVE_SLICE("cave_y", false),
        NETHER_CURRENT("nether", false),
        NETHER_CEILING("nether_roof", true),
        NETHER_SLICE("nether_y", false),
        END_SURFACE("end", true);

        private final String id;
        private final boolean persistent;

        Type(final String id, final boolean persistent) {
            this.id = id;
            this.persistent = persistent;
        }

        public String id() {
            return id;
        }

        /** Whether tiles of this layer are written to the disk cache. */
        public boolean persistent() {
            return persistent;
        }
    }

    public static final MapLayer SURFACE = new MapLayer(Type.SURFACE, 0);
    public static final MapLayer CAVE_AUTO = new MapLayer(Type.CAVE_AUTO, 0);
    public static final MapLayer NETHER_CURRENT = new MapLayer(Type.NETHER_CURRENT, 0);
    public static final MapLayer NETHER_CEILING = new MapLayer(Type.NETHER_CEILING, 0);
    public static final MapLayer END_SURFACE = new MapLayer(Type.END_SURFACE, 0);

    public static MapLayer caveSlice(final int y) {
        return new MapLayer(Type.CAVE_SLICE, y);
    }

    public static MapLayer netherSlice(final int y) {
        return new MapLayer(Type.NETHER_SLICE, y);
    }

    public String cacheId() {
        switch (type) {
            case CAVE_SLICE:
            case NETHER_SLICE:
                return type.id() + (param < 0 ? "_m" + (-param) : "_" + param);
            default:
                return type.id();
        }
    }

    /**
     * Reconstructs the {@link MapLayer} that produced a given {@link #cacheId()} string
     * (tile keys and disk-cache paths only carry the id, not the layer object itself).
     */
    public static MapLayer parse(final String cacheId) {
        for (final Type type : Type.values()) {
            if (type != Type.CAVE_SLICE && type != Type.NETHER_SLICE && type.id().equals(cacheId)) {
                return new MapLayer(type, 0);
            }
        }
        final String caveSlicePrefix = Type.CAVE_SLICE.id() + "_";
        if (cacheId.startsWith(caveSlicePrefix)) {
            return new MapLayer(Type.CAVE_SLICE, parseSliceParam(cacheId.substring(caveSlicePrefix.length())));
        }
        final String netherSlicePrefix = Type.NETHER_SLICE.id() + "_";
        if (cacheId.startsWith(netherSlicePrefix)) {
            return new MapLayer(Type.NETHER_SLICE, parseSliceParam(cacheId.substring(netherSlicePrefix.length())));
        }
        throw new IllegalArgumentException("unknown layer cache id: " + cacheId);
    }

    private static int parseSliceParam(final String suffix) {
        return suffix.startsWith("m") ? -Integer.parseInt(suffix.substring(1)) : Integer.parseInt(suffix);
    }
}
