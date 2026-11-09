# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `4963ecb9b584cafaff88d086dff158b02200e82a04d2444315fe4da886de5f16` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `606032e72d00e5d05d4387d52a5579d600b1fd447429a432a91786191b23b0da` | 1,441,928 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `90734c8028c928dffa6999bb753cffe40d0573ec9a89300ebe92c0a4a5ecc545` | 1,343,120 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `4364982122b155bd178be72c00fc7ed9d6a09022a8b5791a403fb4d97294370a` | 1,123,155 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `f1c6521017402359abbd1c35e1a33039323bf55f2600f8a50a47fe15dc27e78d` | 1,098,856 bytes |

## Web Map WASM Asset Provenance

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `2c26b1148b7ea83de1ed9b0911be7d327ed31ff1309b69a9c33e5e18961e4e83` | 1,399,729 bytes |

