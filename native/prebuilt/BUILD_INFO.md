# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `0a715cb5226003592ecba5d6630b71c74685a521025fe29e35411a609d5b181f` | 1,554,944 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `f244029f527c77eff22d7cdb9c094f94e3b9bc4f6aa98e27437c2f252e59369e` | 1,441,416 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `20d242e901260780063e04c7aa8cb669a61504e9e707f740bb9de3473e674436` | 1,343,248 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `dd7d738f4764699a160bf753cf0857d872f1220ca1249758ffa3a78a68d57c01` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `b18c420ac84a911e9f9a1fe669310ceea24df873acc94174a15f870023194bf9` | 1,098,888 bytes |

## Web Map WASM Asset Provenance

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `a84c76c8442d00636eb426b574aeb78bc6caed86a9544aacd37234a85e79b298` | 1,399,397 bytes |
