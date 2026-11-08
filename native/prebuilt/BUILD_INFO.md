# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `681abe917417a69a0765bb04ec17e2284efa65b858158e994ba9df015316319c` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `d6d70ae8beab298538f0d4077094c4b535216d162e815b2170e6e3a49f9a92cf` | 1,442,168 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `65a9a1e142d4c567153e07c6738e933358173c887e7b32066616aea97f3737ad` | 1,343,488 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `e41bb2ac5c6d7a2854a42fe8231c0137a4642eb6467d6e4f768ec9a7930676bd` | 1,123,196 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `159ab17496a381c27b237d5329c2f76a5cf080efc57f97594077c251e3433bc3` | 1,098,888 bytes |
