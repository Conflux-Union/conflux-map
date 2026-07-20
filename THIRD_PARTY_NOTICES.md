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
| [preprocessor](https://github.com/Fallen-Breath/preprocessor) (Fallen_Breath's fork of [ReplayMod/preprocessor](https://github.com/ReplayMod/preprocessor)) | GPL-3.0 | Gradle plugin (`com.replaymod.preprocess`) that manages the multi-version source layout under `versions/`. Contributes no runtime classes; this repo started from its example-mod template, since fully replaced. |

## Native code

cubiomes is a git submodule at `native/cubiomes/`; the JNI headers are vendored
at `native/jni/`. Both build the optional seed-prediction library under
`native/`. See [`native/README.md`](native/README.md) and
`native/CUBIOMES_COMMIT` for the pinned commit.

| Component | License | Role |
|---|---|---|
| [cubiomes](https://github.com/Cubitect/cubiomes) by Cubitect | MIT | Git submodule at `native/cubiomes/` pointing to this project's fork [`Conflux-Union/cubiomes`](https://github.com/Conflux-Union/cubiomes), pinned to commit `32a7299`. Compiled with this project's own `native/shim/confluxnative.c` into `native/prebuilt/<target>/`, which **is committed and bundled inside the jar** so the mod ships a working predictor without requiring a C toolchain. Loaded at runtime by `cn.net.rms.confluxmap.nativepredict.NativeLib`. |
| OpenJDK JNI headers (`jni.h`, `jni_md.h`) from a local Eclipse Temurin 21 JDK | GPL-2.0 WITH Classpath-exception-2.0 | Vendored at `native/jni/` so the shim can compile against the JNI ABI. Build-time only; not bundled in the jar. |

## Bundled assets

| Component | License | Role |
|---|---|---|
| [Entity-Icons](https://github.com/Simplexity-Development/Entity-Icons) by Simplexity-Development | CC0-1.0 | Source of the hand-drawn mob face icons at `assets/confluxmap/textures/radar/entity_icons.png` (a 13×15 sheet of 16×16 icons), used for radar / minimap entity markers. CC0 license text ships alongside it. |

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
