# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `7aef2c5a7a04b0d97c9176e7baf565b08ca5790ebb2a122ad3becbf1f64bbdee` | 1,555,456 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `25e361d4e60917ba6fe2e21774bab8298916462deae350426e59f25981553bff` | 1,441,552 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `c50a2592d11e38f8e5334f8699d402c34c35028899c819d1f00f6104313a1383` | 1,342,680 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `f74c2c1d7b09e16656906155c213807f39d4c022507e4a72a7e07323c90fa24c` | 1,123,042 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `bf9a70a44fd792d9e0e6dbd1d50cb58828291dca3d06b4f1e51b731f5472d208` | 1,098,744 bytes |

## Web Map WASM Asset Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `d1d142a855d49281ed4c03b8a472400ca747e1b38e4cbe2ffca287f45f9fea48` | 1,401,225 bytes |
