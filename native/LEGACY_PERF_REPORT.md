# Pre-1.18 (1.17.1) predicted-map overview: acceleration report

The 1.18+ overview already solves through a spatial-adaptive macro grid
(perf/cpu-noise-simd-avx2). The pre-1.18 generator kept master's per-pixel
path, which left 1.17.1 tiles paying an order of magnitude more than their
1.18+ counterparts. This report documents the acceleration applied to the
pre-1.18 overview in `native/shim/confluxnative.c` and its web mirror
`native/web/confluxpredict.c`, and the accuracy evidence that the speedup is
safe.

## Where the time went (master, MC 1.17.1, 258x258 tile window)

Measured with `native/bench_legacy.c` (single-thread, host compile, seed
146008555, fresh origin per iteration so the pipeline's per-tile call
sequence is reproduced; min of 3 runs):

| LOD | stride | overview | surfaceBiomes regen | master total/tile |
|---|---|---|---|---|
| 0 | 1 | 47.7 ms | 0.21 ms | ~47.9 ms |
| 1 | 2 | 50.7 ms | 0.77 ms | ~51.5 ms |
| 2 | 4 | 76.6 ms | 9.63 ms | ~86.2 ms |
| 3 | 8 | 91.1 ms | 20.1 ms | ~111.2 ms |
| 4 | 16 | 121.4 ms | 45.8 ms | ~167.2 ms |

Three separate costs dominate, in different proportions per LOD:

1. **Per-pixel 16-octave depth-noise solves.** `cfxLegacyOverviewHeight`
   evaluated `sampleOctaveAmp(&sn.octdepth, ...)` at every output pixel
   (~0.65 us per solve at stride 1: ~43 ms of every LOD0 tile).
2. **Per-row scale-4 biome generation.** `cfxGenerateBiomesStrided` re-ran the
   full 1.17 layer stack once per sample row; a LOD4 tile issued 258 stack
   traversals over a 1029-cell span (~54-66 ms).
3. **Duplicated biome generation.** Every tile generated the identical strided
   biome window twice - once inside `cfxOverviewHeights`, once through
   `cfxSurfaceBiomes` (pre-1.18 falls back to the same biome query because it
   has no voronoi-nucleus surface sampler).

## The four changes

1. **Quart macro lattice for stride < 4** (LOD0-1). The biome-independent
   overview base `0.53125 + densityOffset(x,z)` depends only on the world
   position - vanilla 1.17 itself evaluates the depth noise per quart column -
   so it is solved once per world-aligned 4x4-block cell corner and bilinearly
   interpolated per output pixel, exactly mirroring the 1.18+ macro grid's
   structure. Per-biome depth stays per-pixel. LOD0 drops from ~66k solves to
   ~4.4k.
2. **Octave truncation for stride >= 4** (LOD2-4) and for the lattice corners
   themselves. `octdepth` stores 16 octaves finest-first (omin=-15, octave i
   carries wavelength 2^i/50 blocks and amplitude 2^i/65535); windows whose
   neighbouring pixels are `stride` blocks apart skip the octaves shorter than
   half the sampling stride - the same "one octave beyond the strict Nyquist
   limit" cut the modern coarse lattices apply. The eight finest octaves hold
   0.39% of the total amplitude. Waterline micro queries (1x1 stride-4) and
   the small exact-anchor grids stay on the full-detail per-pixel entry
   bit-for-bit (below `CFX_LEGACY_TRUNCATE_MIN_CELLS`).
3. **Banded dense biome generation at scale >= 4.** Scales above the Voronoi
   zoom are query-shape invariant, so the sample rows share one dense 2D
   generation (chunked at `CFX_MAX_CELLS`); the read-out is bit-identical to
   the per-row form.
4. **Two-slot biome window memo.** The tile pipeline's second biome request
   (the `cfxSurfaceBiomes` pass, after the LOD0-1 exact-residual anchors
   interleaved between them) becomes a memcpy of the grid the overview pass
   already built. Keys are (x, z, w, h, stride) against thread-confined
   contexts; the web mirror invalidates its slots in `cfxWebInit`.

## Results (same benchmark, optimized)

| LOD | stride | optimized overview | memo hit | optimized total/tile | speedup vs master |
|---|---|---|---|---|---|
| 0 | 1 | 1.89 ms | 0.005 ms | ~1.90 ms | **25x** |
| 1 | 2 | 5.87 ms | 0.005 ms | ~5.87 ms | **8.8x** |
| 2 | 4 | 20.98 ms | 0.005 ms | ~20.99 ms | **4.1x** |
| 3 | 8 | 22.34 ms | 0.006 ms | ~22.35 ms | **5.0x** |
| 4 | 16 | 32.00 ms | 0.006 ms | ~32.01 ms | **5.2x** |

1.17.1 LOD0 (~1.9 ms/tile) now sits well below the 1.18+ optimized LOD0 tile
(~20.7 ms, perf/cpu-noise-simd-avx2), as expected for a 2D closed-form terrain
model. The remaining LOD2-4 cost is the span-sized scale-4 biome field
(13-18 ms at LOD4) plus 66.5k truncated octave solves; both have known further
levers (enabling the Java-side coarse lattice for pre-1.18, which requires a
batched micro-query entry to keep the waterline band affordable - measured
15 us per 1x1 legacy query vs 0.39 us/cell batched) left for a follow-up.

## Accuracy evidence

`native/test_legacy_matrix.c` (3 seeds x 6 origins x 5 strides, tile geometry,
~6M cells) compares the accelerated paths against two references:

- **Frozen per-pixel reference** (master's formula, kept bit-for-bit in
  `cfxLegacyOverviewHeight`, over master's per-row biome sampling):
  mean height drift 0.012 blocks at stride < 4, 0.006 blocks at stride >= 4,
  **max drift 1 block**, 0 cells beyond +/-1, waterline flip rate 0.016% /
  0.006%.
- **cubiomes exact columns** (`mapOverworldSurfaceColumns`, full-tile sweeps):
  MAE 3.5-5.5 blocks, p95 9-19, within the Java quality contract
  (MAE <= 8, p95 <= 24) that
  `PredictionNativeIntegrationTest.overviewPipelineHasTheSameQualityContractAcrossVersionsAndLods`
  pins for 1.17.1.
- **Hard bit-equality gates**: banded dense biomes == master's per-row
  sampling at every stride; the two-slot memo is transparent; 1x1 stride-4
  micro queries and 34x34 stride-8 exact-anchor grids reproduce master
  bit-for-bit; overlapping windows agree cell-for-cell (world-aligned
  lattice, no seams).
- `native/test_web_legacy.c` pins the web predictor's pre-1.18 overview to the
  JNI shim bit-for-bit at every LOD and verifies `cfxWebInit` invalidates the
  biome memo across seed changes.

`PredictionNativeBatchEdgeParityTest`'s batch-vs-single overview contract
moves from bit-equality to a one-block drift bound (the biome corruption it
guards against displaces the surface by two or more blocks); the
Voronoi-margin biome behaviour itself is unchanged.

## Rebuilding

The committed prebuilt binaries and `predictor.wasm` were refreshed with
`buildNativesAll` and `buildWebPredictor` (zig 0.13.0; the wasm target needs
the `-mexec-model=reactor` flag that this branch adds to `buildWebPredictor`).
