# Pre-1.18 overview: full-scale block-by-block validation

Three-way, per-block comparison over 10 random seeds x 5 regions x 4096x4096
blocks (1,117,388,800 sampled columns across LOD strides 1/2/4/8/16):

- **opt** - this branch's accelerated pre-1.18 overview dispatch
  (`cfxOverviewHeightsImpl` legacy paths + memoized biome windows).
- **mast** - origin/master semantics, rebuilt from the unchanged primitives
  (bit-identical per-pixel formula over master's per-row / dense biome
  sampling).
- **exact** - cubiomes' vanilla terrain model (`mapOverworldSurfaceColumns`):
  the same oracle the project's in-game GameTests calibrate against. The
  desktop harness cannot launch Minecraft itself; for true in-game runs the
  existing `PredictionQualityGameTest` remains the mechanism.

Regions (region corners as requested; 1.17 terrain generation is defined out
to the 30M border, and cubiomes' precision wrap extends to +-33.5M, so the
30M corners evaluate cleanly): (0,0), (5M,5M), (-5M,-5M), (30M,30M),
(-30M,-30M). Seeds drawn pseudorandomly (fixed draw, reproducible):
1509797269253326924, 4589371586495684741, 4128368105411603888,
1778750403292628679, 67986890840262971, 3515529913517636733,
4539869800134215359, 374269928810421942, 1958584886842335593,
4377252966816779461.

Harness: `native/test_legacy_fullscale.c` (one process per seed/region unit).
For each stride and comparator, pool MAE and percentage rates by cell count,
sum artifact counters and histogram bins, and take the maximum of unit maxima.
The one-off aggregation helper and original reports are retained only in an
ignored local archive under `build/reports/legacy-predict/`.

These are historical branch measurements, not a new billion-column run.
On 2026-11-08, the retained 50 unit outputs were reaggregated to include all
LOD3/4 units and correct the old helper's summation of maximum errors.

## Verdict

**The accelerated overview remains close to the master-equivalent reference
on these samples**, with small measured differences rather than bit equality.
Both retain the raw overview's approximation gap to the exact model;
downstream exact-anchor residuals reduce it (the production output measured
separately below lands at MAE 1.14 blocks).

## Raw overview vs vanilla exact (pooled, all 50 units)

| stride | cells | MAE opt | MAE mast | water flip opt | mast | depth-bucket mismatch opt | mast | biome agree opt | mast |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 838,860,800 | 7.7158 | 7.7152 | 18.3698% | 18.3698% | 22.8061% | 22.8059% | 100.0000% | 100.0000% |
| 2 | 209,715,200 | 9.2987 | 9.2983 | 23.1414% | 23.1415% | 27.7523% | 27.7521% | 100.0000% | 100.0000% |
| 4 | 52,428,800 | 11.0621 | 11.0621 | 28.6653% | 28.6653% | 33.4545% | 33.4545% | (scale-4) | (scale-4) |
| 8 | 13,107,200 | 12.7869 | 12.7869 | 34.5708% | 34.5706% | 39.6680% | 39.6677% | (scale-4) | (scale-4) |
| 16 | 3,276,800 | 14.1787 | 14.1787 | 38.9775% | 38.9765% | 43.8062% | 43.8054% | (scale-4) | (scale-4) |

- Height: opt-mast mean-MAE delta 0.0006 blocks at stride 1 and 0.0004 at
  stride 2; strides 4/8/16 agree to four decimals. At stride 1, <=4-block hit
  rates are 48.84% (opt) and 48.85% (mast); <=2-block rates both round to 32.30%.
- Biome: 100.0000% block-level agreement with vanilla's single-query semantics
  at stride 1-2 across all 1.05 billion scale-1 comparisons - no boundary
  corruption from the memo or banded generation. At stride >= 4 the biome grid
  is the established scale-4 quart semantics (master's own), bit-identical
  opt-vs-mast (pinned by `test_legacy_matrix.c`).
- The large absolute MAE/water-flip numbers are the raw overview's intrinsic
  approximation gap - the closed-form height carries no exact-anchor
  correction. The master-equivalent comparator shows similar numbers;
  this is what the LOD0-1 residual
  expansion and the fluid-confidence pass exist to fix.

## Production output (post-residual, post-fluid) vs vanilla

`PredictionLegacyFullScalePipelineBenchmarkTest` (`:common:benchmark`, the
real `LodSampling` LOD0 pipeline incl. exact-anchor residual correction and
fluid resolution) over 2 seeds x 2 regions x 4096^2 (67.1M cells):

| metric | value |
|---|---|
| height MAE | **1.1427 blocks** (p95 = 4) |
| waterline flip (height<62) | 3.99% |
| surface kind (land/water flag) mismatch | 2.74% |
| fluid depth bucket mismatch | 6.05% |
| biome mismatch | **0.0000%** |

per-unit: MAE 1.09-1.22, flips 3.40-4.43% - uniform across spawn, 5M and both
signs of coordinates.

## Sudden local changes (the PR154-style 5-point-sampling concern)

This branch does not use PR154's 5-point cross detection + refinement. The
concern translates to two failure modes for any interpolated overview: it
invents cliffs the real terrain lacks, or swallows real ones, ideally as
isolated spikes. Measured at stride 1 over all 838.9M cells (gradient =
max 1-block step; thresholds: cliff >= 6 vs <= 2; spike = |diff| >= 10 with
all four neighbours within 3):

| counter | opt | mast | opt-mast |
|---|---|---|---|
| false cliff (2.88% of cells) | 24,164,053 | 24,142,169 | +21,884 (+0.0026%) |
| missed cliff (0.32% of cells) | 2,703,785 | 2,703,787 | -2 |
| isolated spike (0.00048%) | 4,004 | 4,015 | **-11** |
| \|gradC-gradE\| hist 0/1/2/3-4/5-6/7-10/>10 | 490.4M/253.3M/37.4M/14.8M/5.8M/9.2M/14.9M | 489.5M/254.3M/37.3M/14.7M/5.8M/9.2M/14.9M | ~noise |

Both comparators have substantial raw-overview false-cliff/missed-cliff
counts. Biome-depth steps enter the closed form unblended while vanilla
blends them over a 5x5 kernel. The measured differences above are small,
but these counters alone do not establish the cause of each artifact.

The lattice samples every 4x4-cell
corner (no 5-point subsampling of cells), the kept depth-noise octaves have
wavelengths >= 5.12 blocks (one octave beyond the 4-block cell Nyquist), and
1.17's sharp terrain features - riverbanks, beach drops, biome cliffs - are
carried by the per-biome depth term which is applied per pixel and never
interpolated. Empirically the interpolated field produces 11 fewer isolated
spikes and 2 fewer missed cliffs than master's per-pixel formula, along with
21,884 more false-cliff detections. The gradient-difference histograms are
similar at the reported scale; this is sampled evidence, not a guarantee
that no new local artifacts can occur.

## Notes

- Maximum absolute height deviation vs exact is 169/169/188/197/196 blocks
  at strides 1/2/4/8/16, respectively, for both comparators. The previous
  ~5.2k claim incorrectly summed unit maxima and does not establish an
  exact-oracle failure.
- Region-wise MAE spread at stride 1: 7.51 (R4 = -30M) to 8.16 (R0 = spawn) -
  no degradation toward the coordinate extremes.
