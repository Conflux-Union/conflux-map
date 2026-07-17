# Third-Party Notices

Conflux Map is licensed under the **GNU General Public License v3.0** (see
[`LICENSE`](LICENSE)). This file lists every third-party component involved in
building or running the mod, and the provenance of anything written by looking
at another project's behavior.

This list was produced by auditing the source tree for attribution comments
and by reviewing every build dependency declared in `common.gradle` /
`versions/*/gradle.properties`. No code, identifiers, textures, or other
assets from Dynmap, BlueMap, or cubiomes have been copied or adapted into this
project - none of those projects are referenced anywhere in the source, and
none of their code exists here. If that ever changes (e.g. a cubiomes-based
`spi.PredictionProvider` implementation lands in a later milestone), the
adapted file(s) and their license must be listed below at that time.

## Build-time dependencies

These are not copied into this repository; Gradle fetches them during the
build. Neither is bundled inside the mod's output jar.

| Component | License | Role |
|---|---|---|
| [Fabric API](https://github.com/FabricMC/fabric-api) | Apache License 2.0 | A separate Fabric Loader mod this project depends on (`modImplementation` in `common.gradle`, declared in `fabric.mod.json`'s `depends`) for `HudRenderCallback`, `ClientTickEvents`, `KeyBindingHelper`, `ClientPlayConnectionEvents`, `WorldRenderEvents`, and resource-reload hooks. End users must have Fabric API installed alongside this mod; it ships as its own jar, not inside ours. |
| [preprocessor](https://github.com/Fallen-Breath/preprocessor) (Fallen_Breath's fork of [ReplayMod/preprocessor](https://github.com/ReplayMod/preprocessor)) | GPL-3.0 | A Gradle plugin (`com.replaymod.preprocess`, applied in `build.gradle`/`settings.gradle`) that manages the multi-Minecraft-version source layout under `versions/` and will drive the `//#if` conditional blocks once a second version is added (see the M1 plan). It contributes no runtime classes to the built mod - it only runs as part of the Gradle build itself. This repository started from this plugin's own example-mod template; the template's placeholder mod code has since been fully replaced by Conflux Map's own implementation. |

## Bundled assets

| Component | License | Role |
|---|---|---|
| [Entity-Icons](https://github.com/Simplexity-Development/Entity-Icons) by Simplexity-Development | CC0-1.0 (public domain) | Source of the hand-drawn mob face icons bundled at `assets/confluxmap/textures/radar/entity_icons.png` (a 208x240px, 13-col x 15-row sheet of 16x16 icons), used for radar/minimap entity markers instead of live in-game skin/mob-texture crops. The CC0 license text ships alongside it at `assets/confluxmap/textures/radar/ENTITY_ICONS_LICENSE.txt`. |

## Behavior references (no code or assets used)

| Project | License (as published) | How it was used |
|---|---|---|
| [VoxelMap](https://www.curseforge.com/minecraft/mc-mods/voxelmap) / [VoxelMap-Updated](https://modrinth.com/mod/voxelmap-updated) | All Rights Reserved (VoxelMap-Updated ships no `LICENSE` file and is listed ARR on Modrinth) | Read only as a **black-box behavior reference** in a read-only clone kept outside this repository, to understand what a comparable minimap/waypoint/radar mod's *observable behavior* looks like (zoom steps, waypoint edge-indicator rules, radar color conventions, cave/nether layer heuristics, etc). No source code, textures, strings, package layout, class/field names, or other assets were copied. See [`docs/reference-specs/README.md`](docs/reference-specs/README.md) for the clean-room workflow that turned those observations into implementation-agnostic behavior specs before any code was written. |
| Xaero's Minimap / Xaero's World Map | All Rights Reserved | Same as above: behavior reference only (general minimap/fullscreen-map UX conventions), no code or assets used. |

## Everything else

Every other file under `src/`, `docs/reference-specs/`, and `versions/` is
original work written for Conflux Map, licensed under GPL-3.0 along with the
rest of the project.
