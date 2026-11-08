# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `74cbcdfc8108c4d54ece7a3d6b3a44f96fb0349a623c709a016a71ec9b8badf0` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `f749b685a6af0c858456cf2a6d190d045990757c8a3a45a263dc75049c441f89` | 1,442,360 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `b1bd0565d5da56e59fb52e57a17d2cc213ed6ecf08d693dd6472dbc3396803bf` | 1,343,472 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `2d62d34f976e06c8006a5f3e51b4652c58cba1f29fffca3fee60dac95825e9a0` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `cb00a02fc09ca4c78aabf441319e81aa882de8e84c87bb2b59ffa9299d9cc08c` | 1,098,888 bytes |

## Web Assembly Asset

| Target | Binary Asset | SHA-256 Checksum | Size |
|---|---|---|---|
| Web (Browser) | `common/src/main/resources/webmap/predictor.wasm` | `29b88c3b52b5edcc3355a584fe705c4fdbdc25b8cbb4badf966b3e24f942ee08` | 1,400,018 bytes |
