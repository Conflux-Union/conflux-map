# PR154 预测地图耗时、审查与优化报告

本次基于最新 PR 提交 `c9ecc9c9` 完成审查，优化分支为 `codex/predict-pipeline-performance`。结果：首次探索新区域的冷加载基本持平，未达到整体 4 倍；服务器修正/验证刷新从约 1.2–1.8 秒降至约 46 毫秒，提升约 26–39 倍。下面的阶段表包含各模块耗时与占比，视口表包含端到端 CPU 就绪时间。测试没有覆盖 GPU 绘制、网络或完整实机帧延迟，因此不能将其中某个倍数直接称为整体玩家体感提升。

发现并修复两项 P2：原生质量审计的群系采样坐标错误，以及增量构建遗漏算法头文件输入。最终 138 项相关测试全部通过；18 张瓦片的高度、群系、流体和最终像素逐值一致，36/36 组视口哈希也一致。全量测试唯一失败项为 Windows 无法创建符号链接的环境问题。

没有提交降低采样密度的方案：实验出现大范围海陆错误，已否决。后续应优先测量真实客户端的上传与首帧，优化精确地表柱和群系采样复用，并减少剩余单点 JNI 查询；当前证据不支持承诺首次探索达到 4 倍。

Reviewed PR: https://github.com/Conflux-Union/conflux-map/pull/154, head `c9ecc9c95d61df5c752714a42d22cc1ca6891c34` (2026-11-08). The PR's native overview solver improves its measured isolated CPU workload, but that number is not the same as the time a player waits for a viewport: exact columns, biome sampling, Java scheduling, and render-frame upload limits remain in that path.

## Review Findings

1. **P2: Biome quality audit sampled the wrong coordinates.** `native/test_full_matrix.c` originally passed block X/Z directly to `sampleBiomeNoise`, whose inputs are quart Voronoi coordinates, and skipped production's `voronoiAccess3D`. The resulting biome/coast flip rates did not measure the production surface-biome lookup and could not support the quality claim. The local branch corrects the helper to use production's sea-level clamp, Voronoi access, and noise lookup, and checks 20 positions against the JNI entry point.
2. **P2: Native host build did not track the algorithm header.** `buildNativesHost` listed C source files as inputs but omitted `native/shim/adaptive_macro.h`. Gradle could report the host library up to date after the algorithm header changed. The local branch declares the header as an input.

The new visual snapshot assertions in the latest PR commit also pin the legacy exact contract and cap modern ocean-to-land flips at 0.3%. This is an opt-in benchmark test, not a test automatically executed by ordinary `:common:check`. No quality gate was relaxed in this work.

## Timing Method

Measurements ran on Windows 11, Java 21.0.12.1, 32 reported logical processors, cubiomes `07f828299518`, seed `146008555`, and Minecraft 1.21.4. Stage tests warmed each case twice and measured three runs at three origins. The stage table averages the per-origin medians; proxy-attributed native calls are a separate diagnostic and carry timing overhead. Viewport measurements requested 16 tiles with four workers, one warmup, three measured rounds, and a simulated 60Hz consumer capped at eight uploads per frame. “Cold” means an empty tile cache, not a new process. GPU upload/drawing, network and server delays were not measured.

| LOD | One tile total | Sampling ms (%) | Derive ms (%) | Canopy ms (%) | Compose ms (%) |
|---:|---:|---:|---:|---:|---:|
| 0 | 287.7 ms | 281.1 (97.7%) | 0.7 (0.2%) | 2.1 (0.7%) | 3.6 (1.3%) |
| 1 | 197.1 ms | 190.6 (96.7%) | 0.6 (0.3%) | 2.2 (1.1%) | 3.7 (1.9%) |
| 2 | 243.1 ms | 238.4 (98.1%) | 0.6 (0.3%) | 0.5 (0.2%) | 3.6 (1.5%) |
| 3 | 334.2 ms | 329.3 (98.6%) | 0.7 (0.2%) | 0.5 (0.2%) | 3.7 (1.1%) |
| 4 | 449.6 ms | 444.2 (98.8%) | 0.9 (0.2%) | 0.5 (0.1%) | 4.2 (0.9%) |

Sampling call attribution explains which native work dominates. At LOD0, exact surface columns took about 241 ms, surface biomes 27 ms, and overview heights 20 ms. At LOD1, columns and surface biomes took about 74 ms each, with about 40 ms in overview heights. At LOD2–4, the dominant calls were surface biomes (101–191 ms), overview heights (67–180 ms), and coarse overview heights (59–61 ms). Java's derive, canopy and compose stages are small beside those calls. This is why accelerating only the overview-height kernel has a limited ceiling for the complete path.

## Viewport Results

Median time for all 16 tiles to become available to the simulated upload consumer:

| Scenario | LOD | PR154 baseline | Optimized branch | Speedup |
|---|---:|---:|---:|---:|
| Cold | 0 | 1216 ms | 1226 ms | 0.99x |
| Cold | 2 | 1167 ms | 1214 ms | 0.96x |
| Cold | 4 | 1835 ms | 1808 ms | 1.01x |
| Correction update | 0 | 1215 ms | 46 ms | 26.2x |
| Correction update | 2 | 1201 ms | 46 ms | 26.1x |
| Correction update | 4 | 1785 ms | 46 ms | 38.5x |
| Validation only | 0 | 1245 ms | 46 ms | 26.9x |
| Validation only | 2 | 1213 ms | 46 ms | 26.1x |
| Validation only | 4 | 1809 ms | 47 ms | 38.7x |

Cold time is effectively unchanged within run-to-run variance; this is not a demonstrated cold-path improvement, and the LOD2 ~4% difference remains a limitation. Earlier LOD2 baseline rounds ranged from 1211 to 1399 ms; the final viewport-only rerun was 1167 ms, so no cold speedup is claimed. Both final viewport reports use standalone fresh test JVMs with the same warmup protocol. The measured cached refresh path is substantially faster because correction and validation updates reuse the exact uncorrected baseline planes, then reapply current corrections and styling. All 36 before/after viewport pixel hashes match. Refresh first-tile readiness falls to roughly 30–31 ms. Benefit requires the baseline still to be in the bounded cache. The 4x target is not achieved for first-time exploration; the benchmark does not support claiming a 4x whole-game or GPU-visible speedup.

## Changes and Quality

- Reuse a bounded LRU of directly sampled, immutable baseline planes when corrections invalidate the derived tile cache. Its identity includes session, reload generation, seed, version, preset, quadrant layout and Nether height. It is separate from correction state and cleared on session/reload; at most 64 entries retain about 16.2 MiB of shared planes, without copying arrays at insertion. Superflat tiles bypass this cache.
- Batch adjacent refined biome cells into one row query. The LOD2–4 all-refined test workload drops JNI calls from 16,642 to 130 while retaining per-sample coordinates/heights. Before/after Java SHA-256 comparisons cover 16 tiles across versions, LODs, negative coordinates and world-border-adjacent positions. JNI/WASM parity was checked separately by the existing test. Batching does not reduce the number of noise samples, and no stable cold-path multiplier was demonstrated.
- Skip absent 16x16 chunk regions during predicted-pixel masking (up to 65,536 region lookups become 256 for an empty LOD4 tile), and return a newer full-tile upload directly instead of copying/merging two complete planes.
- Test lower residual sampling densities against 1,024 exact columns per tile and the current rendered-pixel gate. LOD0 stride 8→16 reduced within-two height accuracy from 77.54% to 72.57%, with up to 35.23% visible-pixel changes and 38.62% ocean-to-land flips. LOD1 stride 16→32 produced up to 20.85% visible changes and 10.85% ocean-to-land flips. Those approximations were rejected.

Current CI quality floors include mean height MAE ≤2.30, within-two accuracy ≥0.77, coverage ≥0.97, surface kind ≥0.80, fluid ≥0.80, color ≥0.80, and per-sample combined score ≥0.58. The density experiments fail the visual tolerances by a large margin, so the implementation keeps the existing sampling density. Their corpus is a diagnostic experiment, not a substitute for the complete generated-region GameTest corpus.

## Verification and Reproduction

- Before rebasing to the final PR commit, `:common:check` ran 1,596 tests; 1,595 passed and the existing Windows symlink-permission case `WebMapBrandingTest.rejectsSymlinksOutsideTheConfigurationDirectory` failed because this host cannot create the required link. This is not an all-green suite. After rebasing, all 138 targeted tests passed with no skips, including service, sampling, cache, native integration, JNI/WASM parity, masking and upload/merge tests.
- Opt-in `:common:benchmark --tests '*PredictionPipelineBenchmarkTest'` and `--tests '*PredictionViewportBenchmarkTest'` produce the JSON measurements beside this report. Override `PREDICTION_PIPELINE_OUTPUT`, `PREDICTION_PIPELINE_LABEL`, and `CONFLUX_VIEWPORT_BENCHMARK_OUTPUT` to retain reports.
- `native/test_full_matrix.exe --sampling-only` passed the 20-position production biome parity audit using Zig 0.13.0.
- Output parity: 36/36 viewport hashes match. The latest unmodified PR classes generated 18 visual baselines, then the optimized classes compared equal in terrain heights, biome IDs, fluid flags and every rendered pixel (`pr154-optimized-visual-parity.json`). Sampling density was not reduced. No published native or WASM binary was changed by this work.
- Full native 300-case exhaustive quality and complete Minecraft client/server GameTest matrices were not rerun. GitHub queries and fetches used process-local direct access; persistent proxy settings were unchanged. No branch was pushed and no GitHub review was posted.

## Next Optimization Plan

| Priority | Work | Method and acceptance |
|---|---|---|
| 1 | Measure the real client | Trace a tile through request, queue, sampling, coloring, NativeImage, GPU upload and first drawing. Report p50/p95 for new exploration, pan/zoom, revisit and correction. Distinguish coarse preview from final completion. |
| 2 | Reuse exact residual columns | Key a bounded cache by generator identity and absolute column position; measure adjacent-tile and backtracking hit rates and lock/memory overhead. New, never-visited columns still require calculation. |
| 3 | Batch remaining overview points | Add a scatter/batch native entry that preserves the current point model for shoreline microqueries; do not substitute an intentionally different bulk approximation. Require exact per-point parity at negative coordinates, borders and waterline bands. |
| 4 | Improve surface biome reuse | Profile repeated Voronoi nuclei and reuse exact results. Stride ≤2 already has native memoization; a follow-up must demonstrate additional benefit rather than count existing work as a new optimization. |
| 5 | Prioritize visible work | Schedule current/central tiles first and discard stale generation work between native stages. Reuse existing coarse tiles as previews while final detail is pending; report the two completion times separately. |

Broader uniform downsampling is rejected. Any future approximation should be limited to bounded-error stable regions, keep shoreline/biome-boundary refinement, and retain the current gates plus worst-case multi-seed visual review. None of the proposed follow-ups justifies promising a 4x cold-exploration speedup before measurement.

## Data Provenance and Baseline Reproduction

The final tables use `pr154-current-head-baseline-stages.json`, `pr154-current-head-baseline-viewport.json`, and `pr154-optimized-viewport-current.json`. The first two use original PR production bytecode, including `LodSampling` recompiled from `c9ecc9c9`; the same committed Windows DLL is fixed for both sides. Baseline main/API class directories precede optimized classes on the benchmark classpath. To reproduce independently, compile original production classes in a temporary clean PR checkout and run the identical new benchmark test sources against those classes; never use optimized production classes as the baseline. Run CPU-heavy tests sequentially. Use the original PR to generate visual baselines before the optimized comparison, rather than deleting the baseline and treating a self-comparison as validation.

`pipeline-optimized-stages.json` is a separate stage run and should not alone establish a speedup across changing CPU frequency. `pr154-baseline-stages.json`, `pr154-baseline-viewport.json`, and `pipeline-optimized-viewport.json` are historical measurements from the earlier `fe789d33` round, retained for traceability rather than used in the final tables. `pr154-batching-parity.json` records 16 before/after SHA comparisons; `pr154-residual-sampling-quality.json` contains all 96 approximation experiment rows. The opt-in tests also support stage version/LOD/origin filters through `PREDICTION_PIPELINE_VERSIONS`, `PREDICTION_PIPELINE_LODS`, and `PREDICTION_PIPELINE_ORIGINS`.
