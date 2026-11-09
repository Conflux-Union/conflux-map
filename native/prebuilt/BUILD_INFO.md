# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `530128edcabfeac496b4bd68f2002f2726847913ea15f46706b058dd15ed862c` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `fae816053ec44b0ff6e3b546cc23f0a0a57d6fcca15ff013b587e3f401288b63` | 1,441,928 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `79b0b28c60fc6a7e8e9b581ee29692b0ad56bd0e230de66a65384ac228f8d072` | 1,343,120 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `e9200ac0b1970100a78770108dc41957e108faf6f7e89fee0e0a790e0f90e199` | 1,123,155 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `a4870807d45309db05cfb61153cd678286e71a666dcb11139184247f3169c201` | 1,098,856 bytes |

## Web Map WASM Asset Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `60bd75d994f8f47f555a81ae7a3cb41e3675d31c52215179760665eb607ce13d` | 1,399,743 bytes |
