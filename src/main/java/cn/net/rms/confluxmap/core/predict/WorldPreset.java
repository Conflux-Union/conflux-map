package cn.net.rms.confluxmap.core.predict;

/**
 * The recognized shape of one dimension's terrain generator, as far as prediction cares. Detected
 * from the live generator on whichever side owns the world (integrated server in singleplayer,
 * the companion on a dedicated server) and carried per-dimension in the HELLO_POLICY handshake.
 *
 * <p>Three questions per preset, answered by the accessors below:
 * <ul>
 *   <li>{@link #predictable()} - can cubiomes model this generator at all? The debug world and
 *       anything custom (datapack noise settings, single-biome/buffet sources) cannot be
 *       modeled, so prediction is disabled for that dimension rather than drawing fictional
 *       default-worldgen terrain. Superflat is also not cubiomes-predictable, but gets its own
 *       seedless underlay from a {@link FlatBaseline} instead - see {@code
 *       PredictionState#predictable}.
 *   <li>{@link #cubiomesFlags()} - generator flags for {@code setupGenerator}. Large Biomes is
 *       the same layered generator at a different zoom, which cubiomes supports natively.
 *   <li>{@link #terrainApproximate()} - Amplified shares the default biome layout (so biomes,
 *       coastlines, and the height-gated water rule stay correct) but reshapes terrain heights,
 *       which cubiomes' surface approximation cannot follow; relief shading is approximate.
 * </ul>
 *
 * <p>{@link #wireId()} is the 3-bit value carried in HELLO_POLICY's per-dim flag byte. {@code 0}
 * must stay {@code DEFAULT}: a pre-preset server writes zero bits, and a pre-preset client masks
 * the bits away, so both directions degrade to today's behavior.
 */
public enum WorldPreset {
    DEFAULT(0, true, 0, false),
    /** Vanilla Large Biomes: default layered generation with the cubiomes {@code LARGE_BIOMES} zoom flag. */
    LARGE_BIOMES(1, true, 0x1, false),
    /** Vanilla Amplified: default biome layout, reshaped terrain - predicted relief is approximate. */
    AMPLIFIED(2, true, 0, true),
    /** Superflat: nothing for cubiomes to model; predicts from its uniform {@link FlatBaseline} instead. */
    FLAT(3, false, 0, false),
    /** The debug ("block grid") world. */
    DEBUG(4, false, 0, false),
    /** Anything else: datapack noise settings, single-biome/buffet sources, modded generators. */
    CUSTOM(5, false, 0, false);

    private static final WorldPreset[] BY_WIRE_ID = new WorldPreset[8];

    static {
        for (final WorldPreset preset : values()) {
            BY_WIRE_ID[preset.wireId] = preset;
        }
    }

    private final int wireId;
    private final boolean predictable;
    private final int cubiomesFlags;
    private final boolean terrainApproximate;

    WorldPreset(final int wireId, final boolean predictable, final int cubiomesFlags, final boolean terrainApproximate) {
        this.wireId = wireId;
        this.predictable = predictable;
        this.cubiomesFlags = cubiomesFlags;
        this.terrainApproximate = terrainApproximate;
    }

    /** Whether cubiomes can model this generator (superflat predicts via {@link FlatBaseline} regardless). */
    public boolean predictable() {
        return predictable;
    }

    /** {@code setupGenerator} flags for cubiomes contexts predicting this dimension. */
    public int cubiomesFlags() {
        return cubiomesFlags;
    }

    /** Whether predicted terrain relief is only approximate (biomes/coastlines still exact). */
    public boolean terrainApproximate() {
        return terrainApproximate;
    }

    /** The 3-bit wire value for HELLO_POLICY's per-dim flag byte. */
    public int wireId() {
        return wireId;
    }

    /**
     * Decodes a 3-bit wire value. Unknown values (a newer peer's preset this build doesn't know)
     * decode as {@link #CUSTOM}: "recognized as something we can't predict" is the safe reading.
     */
    public static WorldPreset fromWireId(final int wireId) {
        final WorldPreset preset = wireId >= 0 && wireId < BY_WIRE_ID.length ? BY_WIRE_ID[wireId] : null;
        return preset == null ? CUSTOM : preset;
    }
}
