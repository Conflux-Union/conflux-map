# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `c366978d5976ee66d1657ce67eb85764b229157e39053cf2bf5cb938cafa9003` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `618143ce8d4ddb0b23cbf39486f4d5a6eb9e49ddfc46203a5d2a32a3b625beca` | 1,442,104 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `822e9ee48720d3a67db811a8519738740ccd0d91ae902848f90195e418836753` | 1,343,424 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `e68920f26e2def21e4aa92b0f1b020760db5c2f27d66a0bf15efb3b9c5c1340a` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `5e5d034ac839cb589866a0c3fd00ef52d36d2acf4a735442c2e734d952508c83` | 1,098,888 bytes |

## Web Predictor WASM & SHA-256 Checksum

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Web (WASM) | `predictor.wasm` | `wasm32-wasi` | `c8c36b686b23974ad5325ad4214fc65e5ee0b0aba502178c7ca489a754940770` | 1,399,904 bytes |
