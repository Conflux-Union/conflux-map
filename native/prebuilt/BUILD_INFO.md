# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O2 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `db136dbf17c1237287c971a023a6bbb2540a8a749195c9a7a574d26ed62b6351` | 1,448,192 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `d9c3034b73eadbd4651056a3f3d2bb4815b9dfb1d53ad4ff5b350d1b079e744d` | 1,348,328 bytes |
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `0b0ce45959ea882c0737f94fc51291dd7ede7298937da4669aeffddeffd7f23e` | 1,561,600 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `ac612cf450e0a12b7eae958279805b7060c2cedf97126a3711b7f119bad0a3de` | 1,127,303 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `84637c32c8799f80d6a96be371c914fbe82fd9d0a713528cbe59d8ff6746fc5a` | 1,098,952 bytes |

## Web Map WASM Asset Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `5c03bd60c085feed6c3b385943c8ccf1ff4765deca1e99e89df6f696cc3880e9` | 1,399,329 bytes |
