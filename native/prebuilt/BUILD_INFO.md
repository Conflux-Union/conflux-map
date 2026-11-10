# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `7170952beb6290f193cfeb3bc3e25939376838876bcfc61fe9f0cebfe7639b15` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `d26b207d74a0e3532fb7f42625f760d773820a2bccba885c53f4dfdb37c3e689` | 1,442,120 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `db1a9a523c121841eb715a9909afe7129f97aa0e95b22fbe704711dbf9c88dad` | 1,343,344 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `574418250844c167998294f524a09f095c196863ad33f653cce2a7723bd813ea` | 1,123,123 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `2f7d6e832956f3531a37083fac9b0cf781b801c7d7ba6674e39e03859e6112b6` | 1,098,824 bytes |

## Web Map WASM Asset Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `81ad3284bb4e81f32c5f119b4d3ed3ec65a620184cb1c5b72b68b98bc1e0674a` | 1,400,077 bytes |
