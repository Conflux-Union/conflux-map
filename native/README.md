# Native prediction library

This directory holds everything needed to build `confluxnative`, the small JNI
shim (`shim/confluxnative.c`) around [cubiomes](https://github.com/Cubitect/cubiomes),
pulled in as a git submodule (our fork), that the
`cn.net.rms.confluxmap.nativepredict` Java package loads to answer batch
biome/height/structure queries. See `docs/reference-specs/` and the M2 plan
for why this exists; this file is only the build/maintenance side of it.

## Layout

- `cubiomes/` - a git submodule pointing at this project's fork
  [`Conflux-Union/cubiomes`](https://github.com/Conflux-Union/cubiomes) (itself
  a fork of [`Cubitect/cubiomes`](https://github.com/Cubitect/cubiomes)),
  MIT-licensed. Pinned to commit `e61f905` via the submodule gitlink; the
  pinned commit and upstream URL are also recorded in `CUBIOMES_COMMIT` for
  readability. Only the files needed to link
  `setupGenerator`/`applySeed`/`genBiomes`/`mapApproxHeight`/
  `mapEndSurfaceHeight`/`getStructurePos`/`isViableStructurePos` are compiled
  by `buildNativesHost`/`buildNativesAll` (`biomenoise`, `biomes`, `generator`,
  `layers`, `noise`, `finders`, `rng.h`, `tables/`) - upstream utilities this
  project never calls (`quadbase.c/h`, `util.c/h`) ship in the submodule but
  are not compiled. To change cubiomes, commit on the fork and bump the
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
  table, a small Mach-O symtab parse for the two `.dylib`s - see the S1
  implementation notes) - every `Java_...` entry point shows up correctly
  exported and nothing else does.
- `shim/confluxnative.c` - this project's own code (GPL-3.0), the only file
  here that isn't vendored.
- `prebuilt/<target>/<libname>` - **committed** compiled binaries, so a
  contributor without a C toolchain can still build/run/test the mod; `git`
  must not ignore this directory. `common.gradle`'s `processResources` copies
  it into every version subproject's jar (and dev classpath) as `natives/`.

## Building

`buildNativesHost` (root `build.gradle`) compiles `prebuilt/linux-x86_64/` with
the host `cc` - this is what CI/contributors on Linux x86_64 run to refresh
that one target. It is intentionally **not** wired into `build`/`check`;
regenerate it manually and commit the result whenever the shim or the pinned
cubiomes commit changes:

```
./gradlew buildNativesHost
```

`buildNativesAll` cross-compiles every target (`linux-x86_64`, `linux-aarch64`,
`windows-x86_64`, `macos-x86_64`, `macos-aarch64`) using
[zig cc](https://ziglang.org/) as a batteries-included cross C toolchain. It
is maintainer-only tooling for cutting a full release of the native library
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

Both tasks use the same compile flags: `-shared -fPIC -O2 -g0 -fno-fast-math
-ffp-contract=off -fvisibility=hidden -Wall -Inative/jni -Inative/cubiomes`.
`-g0` prevents compiler-version defaults from embedding debug information in
release binaries. `-fno-fast-math`/`-ffp-contract=off` matter for determinism (see the M2 plan's
"Determinism spec") - predictions must be bit-identical run to run and peer
to peer, and both flags disable float reassociation/fused-multiply-add that
would otherwise vary by codegen. `-fvisibility=hidden` keeps every cubiomes
symbol private to the shared library; only the `Java_...` JNI entry points
(explicitly `JNIEXPORT`) are exported (verified with `nm -D` after building).
