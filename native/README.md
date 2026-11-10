# Native support library

This directory holds everything needed to build `confluxnative`, the JNI
shim (`shim/confluxnative.c`) around [cubiomes](https://github.com/Cubitect/cubiomes)
and the allocation-light Anvil NBT scanner (`shim/anvilscan.c`),
pulled in as a git submodule (our fork), that the
`cn.net.rms.confluxmap.nativepredict` Java package loads to answer batch
biome/height/structure queries and selective chunk-NBT scans. See
`docs/reference-specs/predicted-map.md`
for what it predicts; this file is only the build/maintenance side.

## Layout

- `cubiomes/` - a git submodule pointing at this project's fork
  [`Conflux-Union/cubiomes`](https://github.com/Conflux-Union/cubiomes) (a fork
  of the active [`xpple/cubiomes`](https://github.com/xpple/cubiomes) line, which
  continues [`Cubitect/cubiomes`](https://github.com/Cubitect/cubiomes)),
  MIT-licensed. Pinned to commit `095b6010` via the submodule gitlink; the
  pinned commit and upstream URL are also recorded in `CUBIOMES_COMMIT` for
  readability. Only the files needed to link
  `setupGenerator`/`applySeed`/`genBiomes`/`mapApproxHeight`/
  `mapOverworldSurfaceColumns`/`mapOverworldSurfaceBiome`/
  `mapEndSurfaceHeight`/`getChunkNaturalTreeCandidates`/`getStructurePos`/
  `isViableStructurePos`/`isViableEndCityTerrain`/stronghold iteration are compiled
  by `buildNativesHost`/`buildNativesAll` (`biomenoise`, `biomes`, `generator`,
  `layers`, `noise`, `terrainnoise`, `finders`, `terrain_features`,
  `features/end_city`, `features/fortress`, `features/abandoned_camp`,
  `features/stronghold`, `util`, `rng.h`, `tables/`, plus the fork's GPU
  executor `gpu.c`/`opencl_host.c` with headers `gpu.h`/`gpu_cl_kernels.h`/
  `opencl_host.h`) - other upstream utilities
  this project never calls (`quadbase.c/h`, `carver.c/h`, the loot library)
  ship in the submodule but are not compiled. To change
  cubiomes, commit on the fork and bump the
  submodule pin (`git -C native/cubiomes checkout <commit> && git add native/cubiomes`);
  do not keep a divergent local copy.
- `jni/` - `jni.h` + `jni_md.h` copied from a local OpenJDK 21 (Temurin)
  install's `include/` (GPL-2.0 WITH Classpath-exception-2.0, build-time
  only - see `THIRD_PARTY_NOTICES.md`). Only the **Linux** `jni_md.h` variant
  is vendored, and it is reused as-is for every `buildNativesAll` target
  (including Windows/macOS) - this works because `JNIEXPORT` there expands to
  the GNU `__attribute__((visibility("default")))` form, which `zig cc`
  (Clang) honors identically for exporting a symbol from a shared library on
  ELF (Linux), Mach-O (macOS), and PE/COFF when targeting the GNU/MinGW ABI
  (`*-windows-gnu`, what `buildNativesAll` uses - not MSVC, which would need
  `__declspec(dllexport)` instead). Verified for all 5 targets after building
  (`nm -D` for the two Linux `.so`s, `objdump -p` for the `.dll`'s export
  table, a small Mach-O symtab parse for the two `.dylib`) - every `Java_...` entry point shows up correctly
  exported and nothing else does.
- `shim/confluxnative.c` and `shim/anvilscan.c` - this project's own code
  (GPL-3.0). The Anvil scanner receives already-decompressed chunk NBT from
  Java and retains only the status, heightmap, palette and biome fields needed
  by LOD-3/4 correction columns; unrelated NBT payloads are skipped in-place.
- `web/confluxpredict.c` - the browser-only cubiomes wrapper. It produces the
  same margin-inclusive biome, overview-height, exact-residual and fluid
  baseline used by the Java predictor. A Web Worker composes that baseline;
  generated server regions are then drawn over it. `buildWebPredictor` compiles it to the
  committed `common/src/main/resources/webmap/predictor.wasm` asset.
- `prebuilt/<target>/<libname>` - **committed** compiled binaries, so a
  contributor without a C toolchain can still build/run/test the mod; `git`
  must not ignore this directory. `common.gradle`'s `processResources` copies
  it into every version subproject's jar (and dev classpath) as `natives/`.

## Building

`buildWebPredictor` uses `zig cc -target wasm32-wasi` to refresh the browser
predictor. It is intentionally separate from normal builds, which package the
committed WASM artifact and therefore do not require Zig:

```
./gradlew buildWebPredictor
```

The browser wrapper compiles cubiomes' biome and terrain generator. It uses the
same determinism flags and height model as the JNI library plus `-O3`,
dead-section elimination and WASM SIMD. The Web Worker first paints the cheap
terrain overview, then refines LOD 0-1 with the sparse exact residual grid while
keeping prediction work off the page's rendering thread.

`buildNativesHost` (root `build.gradle`) compiles `build/natives/host/linux-x86_64/` with
the host `cc` - this is what CI and local contributors on Linux x86_64 run for local development
and testing without touching prebuilt releases. It is intentionally **not** wired into general
`build`/`check` lifecycle tasks:

```
./gradlew buildNativesHost
```

The same holds for every compiled harness below: they need a host C compiler, so they live behind
explicit tasks and are never part of a version subproject's `check` (Windows and macOS contributors
have no `cc`/`gcc`, and `-Wl,--no-undefined` is GNU-ld only):

```
./gradlew verifyNative       # testNativePurity + testNativeMatrix (300 cases)
./gradlew verifyNativeSmoke  # testNativePuritySmoke + testNativeMatrixSmoke (12 cases)
./gradlew benchNative        # native/bench_single.c, per-LOD single-core solve time
```

Run `verifyNative` or `verifyNativeSmoke` locally before touching anything under `native/`.

`buildNativesAll` is the sole authority that cross-compiles every production target into `native/prebuilt/`
(`linux-x86_64`, `linux-aarch64`, `windows-x86_64`, `macos-x86_64`, `macos-aarch64`)
using [zig cc](https://ziglang.org/) as a batteries-included cross C toolchain.
It is maintainer-only tooling for cutting a full release of the native library
and is expected to fail loudly ("zig required") on machines without `zig` on
`PATH` - that is not a build break, just a signal to either install zig or
fall back to `buildNativesHost` for a same-platform build.

Only `linux-x86_64` is ever exercised by an actual JUnit test in this repo (a
Linux dev/CI box can't execute Windows/macOS/aarch64 binaries) - the other
four are cross-compiled and statically checked (correct exports, right
architecture/format) but not functionally run. Treat them as "should work,
not yet proven on real hardware" until someone runs the mod on that platform.

Zig target triples used, and the resulting file per OS:

| OS | zig target | output |
|---|---|---|
| Linux x86_64 | `x86_64-linux-gnu.2.17` | `libconfluxnative.so` |
| Linux aarch64 | `aarch64-linux-gnu.2.17` | `libconfluxnative.so` |
| Windows x86_64 | `x86_64-windows-gnu` | `confluxnative.dll` |
| macOS x86_64 | `x86_64-macos` | `libconfluxnative.dylib` |
| macOS aarch64 | `aarch64-macos` | `libconfluxnative.dylib` |

Both tasks use the same compile flags: `-shared -fPIC -O3 -g0 -fno-fast-math
-ffp-contract=off -fvisibility=hidden -Wall -DCUBIOMES_ENABLE_GPU
-Wl,--no-undefined -Inative/jni -Inative/cubiomes`.
`-g0` prevents compiler-version defaults from embedding debug information in
release binaries. `-fno-fast-math`/`-ffp-contract=off` matter for determinism -
predictions must be bit-identical run to run and peer
to peer, and both flags disable float reassociation/fused-multiply-add that
would otherwise vary by codegen. `-fvisibility=hidden` keeps every cubiomes
symbol private to the shared library; only the `Java_...` JNI entry points
(explicitly `JNIEXPORT`) are exported (verified with `nm -D` after building).

`-DCUBIOMES_ENABLE_GPU` compiles the GPU executor (`gpu.c`/`opencl_host.c`)
and the transparent dispatch hooks into `biomenoise.c`'s
`mapOverworldSurfaceBiome`/`mapOverworldTerrainHeight`. The host resolves the
platform's OpenCL loader via dlopen (`libOpenCL.so.1` on Linux, the OpenCL
framework on macOS, `OpenCL.dll` on Windows) at first use - there is no
link-time OpenCL dependency, and a machine without a loader or GPU device
latches the engine dead and silently serves the CPU path. Linux targets
(same glibc 2.17 baseline as before) additionally link `-ldl -lpthread`;
macOS resolves both through libSystem and Windows through kernel32, so
neither wants the POSIX libs. Every GPU kernel runs single precision: the
overview/surface-biome cfx kernels (rect, batch, and sparse refinement
included) re-run the shim's analytic chains in fp32, so their outputs
approximate the CPU reference within the drift budgets the fork's
`gpu_test` harness gates, while CPU fallback and cropped server windows
keep the exact CPU values. The engine mode is overridable at
runtime via `CubiomesNative.cfxSetGpuMode` (library default: env
`CUBIOMES_GPU`, auto when unset). The web predictor is deliberately compiled
without the gate: WebAssembly has no dlopen, so its biomenoise hooks
would be dead weight.
