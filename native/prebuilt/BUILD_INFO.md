# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `8493131ab77f457bba5c89820d8d029dbc42a418a294e141b8999fc755c79419` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `b0ac8b07da5f12cb969807d8346cfd3789dfd0612e42fd5091b9b30a4aa6881a` | 1,442,120 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `c973a4549121a8b2c4eff29c457c0ade166ffa65374540ba59fd624baa47e7a0` | 1,343,392 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `24f029ef50634758978eaa8bfb35467b18068e4fee9c6201a3ef1d77955666d5` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `d1fb754d8f4ca2d1b7884ee6c3d81a8f2e30460529f69aaeee7f29ddbf865684` | 1,098,888 bytes |

## Web Predictor WASM & SHA-256 Checksum

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Web (WASM) | `predictor.wasm` | `wasm32-wasi` | `1eee6fd990925acfc8a2e737cf08a40d0db3471fd15f44bb3a01881cc5b916cd` | 1,399,880 bytes |
