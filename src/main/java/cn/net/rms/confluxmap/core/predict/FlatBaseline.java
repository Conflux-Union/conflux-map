package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.DiffSpec;
import java.util.Arrays;

/**
 * The uniform surface of a superflat dimension: every column is the same (kind, height, map
 * color, biome, fluid depth) sample, so the whole predicted underlay - and the companion's
 * residual-diff baseline - is this one value. Computed from the live {@code
 * FlatChunkGeneratorConfig} on whichever side owns the world ({@code server.FlatWorldBaseline})
 * and carried to multiplayer clients in {@code FLAT_BASELINE_S2C}.
 *
 * <p>No seed and no native library are involved anywhere downstream of this record: a flat
 * underlay composes from constants, and real-world differences (villages, player builds) arrive
 * as ordinary correction patches diffed against the same uniform sample.
 *
 * @param biomeId    cubiomes-space biome id (equal to the 1.17 raw registry id for vanilla biomes)
 * @param surfaceY   Y of the topmost non-air layer (the water surface for water-topped presets)
 * @param kind       {@link cn.net.rms.confluxmap.core.model.SurfaceKind} ordinal of the top layer
 * @param mapColorId vanilla map color of the top block; {@code 255} = none (render via biome palette)
 * @param fluidDepth consecutive water layers below the surface (0 for solid tops)
 */
public record FlatBaseline(int biomeId, int surfaceY, int kind, int mapColorId, int fluidDepth) {

    /** The equivalent per-pixel diff sample, for residual patch building. */
    public DiffSpec.Sample toDiffSample() {
        return new DiffSpec.Sample(biomeId, surfaceY, kind, mapColorId, fluidDepth);
    }

    /** A margin-inclusive grid where every cell is this surface's biome and height. */
    public BaselineGrid toBaselineGrid() {
        final BaselineGrid grid = new BaselineGrid();
        Arrays.fill(grid.biomeId, biomeId);
        Arrays.fill(grid.terrainY, surfaceY);
        return grid;
    }

    /** A margin-inclusive derived grid where every cell is this surface's kind/height/fluid. */
    public DerivedGrid toDerivedGrid() {
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(derived.surfaceY, surfaceY);
        Arrays.fill(derived.kind, (byte) kind);
        Arrays.fill(derived.fluidDepth, fluidDepth);
        return derived;
    }
}
