# Preprocessor Example Mod

**English** | [简体中文](README-CN.md)

Example mod for [Fallen_Breath](https://github.com/Fallen-Breath)'
s [implementation](https://github.com/Fallen-Breath/preprocessor) of [ReplayMod](https://github.com/ReplayMod)'
s [preprocessor](https://github.com/ReplayMod/preprocessor).

## Disclaimer

If you are new to fabric modding, this template is not for you. Please learn to
use [Fabric Example Mod](https://github.com/FabricMC/fabric-example-mod) instead.

## Features

This repository provides some unique features that no other preprocessor template repositories provide, including:

- Automatic Gradle subproject creation and linking (majorly implemented by @Jog_Ming )
- GitHub Actions matrix publishing and cache refresh (majorly implemented by @XRain66 )

## Setup

To set up the project, you will need to change the following fields in specified files:

- `group` and `id` (corresponding to `maven_group` and `archives_base_name` in conventional fabric mods) in
  `gradle.properties`
- The directory and file names in `src/main`
- The `name`, `description`, `authors`, `contact`, and `entrypoints` field in `fabric.mod.json`
- The `package` field in `<modid>.mixins.json`

This template defaults to Minecraft version 1.17.1. To change that, simply rename the folder in `versions` and change
the contents of `versions/mainProject` to the version of desire, and sync Gradle.

To add support for a new version, do:

- Create a new folder in `versions` named to be the version you want to add
- Create a txt file in `versions` named `mapping-<old version>-<new version>.txt`
    - This file is required, but could be left blank. It could also feature the translations of classes between the old
      version and the new version.
    - For example, in 1.17.1, the class responsible for spawning phantoms is `net.minecraft.world.gen.PhantomSpawner`
    - However, in 1.20.1, it was renamed (moved) to `net.minecraft.world.spawner.PhantomSpawner`.
    - To allow this translation to happen automatically, write the entry
      `net.minecraft.world.gen.PhantomSpawner net.minecraft.world.spawner.PhantomSpawner` in
      `versions/mapping-1.17.1-1.20.1.txt`.
    - Each translation entry should occupy one line.
- If you want to add support for more than two versions, make sure that the mapping files are chained together from
  older versions to newer versions
    - For example, if you want to support three versions, 1.17.1, 1.20.1 and 1.21.1, the mapping files should be
      `versions/mapping-1.17.1-1.20.1.txt` and `versions/mapping-1.20.1-1.21.1.txt`
- After adding the mapping txt files, sync Gradle.
- If you want to focus development on a version other than 1.17.1, modify the file `versions/mainProject`, and sync
  Gradle
- In each of the version folders, create a `gradle.properties` file and a `<modid>.accesswidener` file.
    - In `gradle.properties`, specify the fabric loader and the yarn mapping versions to be used. Refer to the examples
      given in `versions/1.17.1/gradle.properties` if unsure
    - `<modid>.accesswidener` is necessary but could have no entries (must declare `accessWidener v2 named` at the very
      start of the file though)

To add a mod as dependency, more changes had to be made:

- Declare the mod repository in `common.gradle`.
- Go to `fabric.mod.json` and make space to insert the dependency declarations.
    - Find the `"depends"` key and insert the dependency declaration like this:
      `"depends": { ..., "carpet": ">=$carpet_version", ... }`
- In every version folder's `gradle.properties` file, create entries to store the dependency's version information. You
  will likely need two entries for each dependency, one for the full version string to be used in dependency
  declaration (such as `carpet_full_version=1.17.1-1.4.57+v220119`), and another for the simplified version string to be
  used in `fabric.mod.json` (such as `carpet_version=1.4.57`)
- In the `dependencies` section of `common.gradle`, insert the respective `modImplementation` or `include`
  declarations. (i.e. `modImplementation "carpet:fabric-carpet:$carpet_full_version"`)
- In the `processResources` section of `common.gradle`, find the line starting with `filesMatching('fabric.mod.json')`,
  and insert a key-value pair like this:
  `filesMatching('fabric.mod.json') { expand ..., carpet_version: carpet_version, ... }`

If you want the GitHub `release` action to automatically publish to Modrinth, make sure to set the environment variable
`MODRINTH_ID` and secret `MODRINTH_TOKEN`. If you have mod dependencies, declare them in `.github/workflows/release.yml`
in the `dependencies` field. Refer to [mc-publish](https://github.com/Kira-NT/mc-publish) for declaration syntax.

For more information on preprocessor, including its exact usage, purpose, and mechanics, refer
to [ReplayMod's repository](https://github.com/ReplayMod/preprocessor).

For more information on changes made by Fallen_Breath, refer
to [his repository](https://github.com/Fallen-Breath/preprocessor).

## License

This template is available under the GPL-3.0 license.
