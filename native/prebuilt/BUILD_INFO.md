# Prebuilt Native Binary Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`
- **Compiler CFlags**: `-shared -fPIC -O3 -g0 -fno-fast-math -ffp-contract=off -fvisibility=hidden -Wall -Wl,--no-undefined -Inative/jni -Inative/cubiomes`
- **Build Task**: `./gradlew buildNativesAll`

## Target Binaries & SHA-256 Checksums

| Target Platform | Binary File | Target Triple | SHA-256 Checksum | Size |
|---|---|---|---|---|
| Windows x86_64 | `windows-x86_64/confluxnative.dll` | `x86_64-windows-gnu` | `c32bb5225f0766d97f517bb7033ccc020765f1ec28bf7582ed158712980f7990` | 1,555,968 bytes |
| Linux x86_64 | `linux-x86_64/libconfluxnative.so` | `x86_64-linux-gnu.2.17` | `46fd6dcaec152aedb83de5c67d0752057ecfe9fb0713d8d5e074c36875b5947f` | 1,442,088 bytes |
| Linux aarch64 | `linux-aarch64/libconfluxnative.so` | `aarch64-linux-gnu.2.17` | `86923ecc382b107502b62d78bd2daf21436a5bf65f04a757a0c10c9dd73cba42` | 1,343,312 bytes |
| macOS x86_64 | `macos-x86_64/libconfluxnative.dylib` | `x86_64-macos` | `e1b0a42e91174b1a1468c18b4dee81b3970a0eb862bcac04384b57d76397ca1d` | 1,123,123 bytes |
| macOS aarch64 | `macos-aarch64/libconfluxnative.dylib` | `aarch64-macos` | `104e2db18ca339291c98b1d7b39a91b54d3b0c62dd38a8b764865476cdd56de2` | 1,098,824 bytes |

## Web Map WASM Asset Provenance

- **Cross-Compiler Toolchain**: `zig cc 0.13.0`

| Asset | Target Triple | Compiler Command | SHA-256 Checksum | Size |
|---|---|---|---|---|
| `common/src/main/resources/webmap/predictor.wasm` | `wasm32-wasi` | `zig cc -target wasm32-wasi -mexec-model=reactor -O3 -g0 -msimd128` | `4b5e6725086a1536f6c4929a4f8fbe7d151df71343ecbf76853a1d902a4beb9a` | 1,400,032 bytes |
