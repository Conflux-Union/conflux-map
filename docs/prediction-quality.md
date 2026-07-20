# Prediction quality benchmark

The prediction quality GameTest compares the production cubiomes prediction pipeline with
complete, normally generated Vanilla regions. It is intentionally a slow correctness benchmark,
not a fast unit test.

## Reference and prediction paths

Fabric's 1.17.1 GameTest server normally replaces the Overworld generator with a flat generator.
The test-only `TestServerMixin` keeps the default Vanilla noise generator so both the Overworld and
the End contain real terrain. This mixin is packaged only in the `gametest` source set and cannot be
loaded from the release mod.

The GameTest server keeps Vanilla structure generation disabled, so this benchmark targets terrain,
biomes, decoration features, and End islands rather than villages or other structures that the
predictor does not attempt to reconstruct.

The benchmark uses world seed `0` because that is fixed by the 1.17.1 GameTest server. A separate,
fixed corpus seed selects eight Overworld tiles and four End tiles. Each sample is a complete LOD 0
tile: 16 by 16 chunks and 256 by 256 output pixels. Samples are kept away from the spawn area so an
existing world cannot accidentally satisfy the benchmark from spawn generation alone.

The reference path reads each generated `WorldChunk` through Vanilla's live motion-blocking
heightmap, block state, `MapColor`, and biome registry APIs, then applies Conflux Map's normal
height and slope shading. The prediction path uses the same native sampler, derivation, canopy, and
tile composer used by the mod. No stored golden images or network service are involved.

This measures terrain prediction and CPU-side map composition. It does not validate GPU texture
uploads, framebuffer behavior, GUI scaling, fonts, or driver-specific rendering. Those remain
client smoke-test concerns rather than deterministic image-quality metrics.

## Metrics

Every sample reports:

- generated-pixel coverage accuracy;
- surface-kind accuracy;
- height mean absolute error, signed bias, P95 error, and the fraction within two blocks;
- water-depth bucket accuracy (`0`, `1-3`, `4-9`, or `10+` blocks);
- normalized RGB similarity;
- global luminance SSIM;
- exact semantic-edge F1 plus a primary edge F1 that tolerates one pixel of boundary displacement;
- a weighted combined score.

The combined score weights coverage at 10%, surface kind at 20%, height-within-two at 20%, fluid
depth at 10%, color at 20%, SSIM at 10%, and one-pixel-tolerant edge F1 at 10%. The exact edge score
remains in the report for strict diagnosis. Absolute floors guard against silent
regressions while the complete raw metrics remain available for diagnosis. Aggregate fluid
accuracy is weighted by the number of reference water pixels, so water-free End samples cannot
inflate it.

## Running and reports

Run the same path used by CI:

```sh
./gradlew :1.17.1:runGametest
```

The report is written under
`versions/1.17.1/build/reports/prediction-quality/`:

- `report.json` contains both seeds plus aggregate and per-sample metrics;
- `index.html` is a compact browsable table;
- the five worst samples include reference, prediction, and heatmap PNG files.

The normal `check` and `build` lifecycles depend on `runGametest`. GitHub Actions uploads the report
directory even when a threshold fails, so the failing images and metrics remain inspectable.
