# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `5e0e595cb9ac444404242226dbb7bda63859cd370bb7667c93efdf41db800a4d` | 1,555,456 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `74e5282f6b12e75ea13488d45fb98929fe2c9eca6a98b2e99fabefd950ec124f` | 1,441,512 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `4135d6813d2bc91a8d5b3e36f51cc5647eb926f0653d4fb1676fd7ebd6aad4d6` | 1,343,264 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `37f7311e943b7e707c8aa82965b9991541715f8ad16b938f3de30ba286fdd101` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `8aaa0edca42e99037d91778d2fb1a7412c521b7fe84dbe4a1a283944e10a1ebf` | 1,098,888 bytes |
