# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `cb55cf4763138b72e5058728d8a7c293675c9664daebccff2397c02bfe8c71b7` | 1,535,016 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `f312ad219d3f11d13f993d0d8beeb45dc337c73db2b336a117b848ddc75e0e0a` | 1,440,408 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `b99e984f8605c31758c0cfc3e5366432a5ce8b1912a7f5a774ea0224157790b9` | 1,328,096 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `e2a4be5a18d1796c56b7c2d25032a225ee4787a419842f1f0a1d471550c6ea63` | 1,099,992 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `1bfad88f117c2a71be768a41764724b07c2921ea02058e0a18cff42be2580c85` | 1,079,064 bytes |
