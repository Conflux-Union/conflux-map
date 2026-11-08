# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `74fb6b52e35e2bf94cc0c6e8e2939249204bd9de79bf2436fc9f12f88138da00` | 1,554,944 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `26ed071bfc35bff386fbfed27792884188b0e8757a2d50c9180e51e69b201daf` | 1,441,416 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `8b663534a60a8a92733b88c4cf2e16b7794c19e38e54953665ebc07061fbab28` | 1,343,248 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `568a2ad3ba25cf2ff1417f81456d0bed03c0e8b12c2b9178c815ef59ed2f0c64` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `4b5864a668019148db435adfdd18b414afe764a5f7dc34684cbaac80744f5fbc` | 1,098,888 bytes |

## Web Predictor WASM & SHA-256 Checksum

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Web (WASM) | `predictor.wasm` | `wasm32-wasi` | `721c59e78e7f7660907439b740172511b98ec67c6d5a3f81e46a6b3e94bbfb4a` | 1,399,422 bytes |
