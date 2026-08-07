# Third-Party Notices

Conflux Map is licensed under the **GNU General Public License v3.0** (see
[`LICENSE`](LICENSE)). This file lists the third-party components used to build
or run the mod, and the provenance of anything written by looking at another
project's behavior. No code, identifiers, textures, or other assets from
Dynmap, BlueMap, VoxelMap, or Xaero's mods have been copied into this project.
cubiomes is used deliberately, under its own MIT license, as the seed-prediction
native library (see "Native code" below).

## Build-time dependencies

Fetched by Gradle during the build; neither is bundled inside the output jar.

| Component | License | Role |
|---|---|---|
| [Fabric API](https://github.com/FabricMC/fabric-api) | Apache-2.0 | A separate Fabric Loader mod this project depends on (`modImplementation`), for render / tick / keybind / resource-reload hooks. End users must install it alongside this mod; it ships as its own jar. |
| [preprocessor](https://github.com/ReplayMod/preprocessor) | GPL-3.0 | Gradle plugin (`com.replaymod.preprocess`) that manages the multi-version source layout under `versions/`. Contributes no runtime classes; this repo started from the example-mod template of [Fallen_Breath's fork](https://github.com/Fallen-Breath/preprocessor), since fully replaced, and `settings.gradle` now resolves the plugin from upstream because the fork rejects the unobfuscated 26.1 version node. |

## Paper plugin runtime dependencies

The standalone Paper companion shades these components into `confluxmap-paper` so server
operators install one plugin jar. They are not added to the Fabric client artifacts.

| Component | License | Role |
|---|---|---|
| [querz-nbt](https://github.com/Canary-Prism/querz-nbt) | Apache-2.0 | Parses bounded, decompressed chunk NBT from Anvil region files without loading or generating Bukkit chunks. |
| [lz4-java](https://github.com/lz4/lz4-java) | Apache-2.0 | Reads the LZ4 chunk-compression variant supported by current Anvil files. |

## Shared runtime dependencies

These components are bundled into both the Fabric and Paper server artifacts.

| Component | License | Role |
|---|---|---|
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | BSD-3-Clause | Serves the optional web-map HTTP assets and binary WebSocket map stream on one configured port. |

## Native code

cubiomes is a git submodule at `native/cubiomes/`; the JNI headers are vendored
at `native/jni/`. Both build the optional seed-prediction library under
`native/`. See [`native/README.md`](native/README.md) and
`native/CUBIOMES_COMMIT` for the pinned commit.

| Component | License | Role |
|---|---|---|
| [cubiomes](https://github.com/Cubitect/cubiomes) by Cubitect | MIT | Git submodule at `native/cubiomes/` pointing to this project's fork [`Conflux-Union/cubiomes`](https://github.com/Conflux-Union/cubiomes), pinned to commit `9afc103`. Compiled with this project's own `native/shim/confluxnative.c` into committed JNI libraries under `native/prebuilt/<target>/`, and with `native/web/confluxpredict.c` into the committed browser WASM predictor. Both are bundled in the jar so prediction does not require a C toolchain at runtime. |
| OpenJDK JNI headers (`jni.h`, `jni_md.h`) from a local Eclipse Temurin 21 JDK | GPL-2.0 WITH Classpath-exception-2.0 | Vendored at `native/jni/` so the shim can compile against the JNI ABI. Build-time only; not bundled in the jar. |

## Bundled assets

| Component | License | Role |
|---|---|---|
| [Entity-Icons](https://github.com/Simplexity-Development/Entity-Icons) by Simplexity-Development | CC0-1.0 | Source of the hand-drawn mob face icons at `assets/confluxmap/textures/radar/entity_icons.png` (a 13×15 sheet of 16×16 icons), used for radar / minimap entity markers. CC0 license text ships alongside it. |
| [Velocity](https://github.com/PaperMC/Velocity) by PaperMC | GPL-3.0 | `velocity_server_current.properties` and `velocity_server_available.properties` contain only the 34 localized values of `velocity.command.server-current-server` and `velocity.command.server-available`, pinned from upstream commit [`2676520`](https://github.com/PaperMC/Velocity/commit/2676520c6a54bac0529544793c982dd701b338d9). They are loaded at runtime to recognize and hide the client-issued `/server` probe responses without guessing from arbitrary chat or MOTD messages. |
| [Leaflet](https://leafletjs.com/) | BSD-2-Clause | The minified browser map runtime and stylesheet are extracted from the pinned `org.webjars.npm:leaflet:1.9.4` artifact into the bundled web-map assets. |

## Behavior references (no code or assets used)

Read only as black-box behavior references, in a read-only clone kept outside
this repository, to understand what a comparable minimap mod's observable
behavior looks like. No source code, textures, strings, package layout, or
identifiers were copied. See [`docs/reference-specs/README.md`](docs/reference-specs/README.md)
for the clean-room workflow.

| Project | License (as published) | How it was used |
|---|---|---|
| [VoxelMap](https://www.curseforge.com/minecraft/mc-mods/voxelmap) / [VoxelMap-Updated](https://modrinth.com/mod/voxelmap-updated) | All Rights Reserved | Behavior reference only: zoom steps, waypoint edge-indicator rules, radar color conventions, cave / nether layer heuristics. |
| Xaero's Minimap / Xaero's World Map | All Rights Reserved | Behavior reference only: general minimap / fullscreen-map UX conventions. |

## Everything else

Every other file under `src/`, `docs/reference-specs/`, `versions/`, and
`native/` (except `native/cubiomes/` and `native/jni/`, both listed above) is
original work written for Conflux Map, licensed under GPL-3.0 — including
`native/shim/confluxnative.c`, the JNI shim around cubiomes. `MapColorTable`
is an original compact ARGB table; its numeric values are a behavior reference
to vanilla map colours, not copied Minecraft code.
