# Preprocessor Example Mod

[English](README.md) | **简体中文**

这是为 [Fallen_Breath](https://github.com/Fallen-Breath)
的 [preprocessor 实现](https://github.com/Fallen-Breath/preprocessor)
而制作的示例模组，该实现基于 [ReplayMod](https://github.com/ReplayMod)
的 [preprocessor](https://github.com/ReplayMod/preprocessor) 。

## 免责声明

如果你是Fabric模组开发的新手，那这个模板不适合你。请先学习使用 [Fabric Example Mod](https://github.com/FabricMC/fabric-example-mod) 。

## 特性

这个仓库提供了一些其他preprocessor模板仓库没有的独特功能，包括：

- 自动创建和链接Gradle子项目（主要由 @Jog_Ming 实现）
- GitHub Actions矩阵发布和缓存刷新（主要由 @XRain66 实现）

## 设置

要设置项目，你需要修改以下指定文件中的字段：

- `gradle.properties` 中的 `group` 和 `id`（对应传统fabric模组中的 `maven_group` 和 `archives_base_name`）
- `src/main` 中的目录和文件名
- `fabric.mod.json` 中的 `name`、`description`、`authors`、`contact` 和 `entrypoints` 字段
- `<modid>.mixins.json` 中的 `package` 字段

这个模板默认使用Minecraft版本1.17.1。要更改版本，只需重命名 `versions` 文件夹中的目录，并将 `versions/mainProject`
的内容改为所需的版本，然后同步Gradle。要添加对新版本的支持，请执行以下操作：

- 在 `versions` 中创建一个新文件夹，命名为你想要添加的版本
- 在 `versions` 中创建一个名为 `mapping-<旧版本>-<新版本>.txt` 的txt文件
    - 这个文件是必需的，但可以留空。它也可以包含旧版本和新版本之间类的转换关系。
    - 例如，在1.17.1中，负责生成幻翼的类是 `net.minecraft.world.gen.PhantomSpawner`
    - 然而，在1.20.1中，它被重命名（移动）为 `net.minecraft.world.spawner.PhantomSpawner`。
    - 为了让这个转换自动进行，在 `versions/mapping-1.17.1-1.20.1.txt` 中写入条目
      `net.minecraft.world.gen.PhantomSpawner net.minecraft.world.spawner.PhantomSpawner`。
    - 每个转换条目应占一行。
- 如果你想要支持两个以上的版本，请确保映射文件是按从旧到新的顺序链接的
    - 例如，如果你想支持三个版本：1.17.1、1.20.1和1.21.1，映射文件应该是 `versions/mapping-1.17.1-1.20.1.txt` 和
      `versions/mapping-1.20.1-1.21.1.txt`
- 添加映射txt文件后，同步Gradle。
- 如果你想专注于开发1.17.1以外的版本，修改文件 `versions/mainProject`，然后同步Gradle
- 在每个版本文件夹中，创建一个 `gradle.properties` 文件和一个 `<modid>.accesswidener` 文件。
    - 在 `gradle.properties` 中，指定要使用的fabric加载器和yarn映射版本。如果不确定，请参考
      `versions/1.17.1/gradle.properties` 中的示例
    - `<modid>.accesswidener` 是必需的，但可以没有条目（不过必须在文件开头声明 `accessWidener v2 named`）

若要添加一个模组作为依赖，则需要进行更多更改：

- 在 `common.gradle` 中声明模组仓库。
- 转到 `fabric.mod.json` 并留出空间插入依赖声明。
    - 找到 `"depends"` 键并插入依赖声明，如下所示：`"depends": { ..., "carpet": ">=$carpet_version", ... }`
- 在每个版本文件夹的 `gradle.properties` 文件中，创建条目来存储依赖的版本信息。每个依赖很可能需要两个条目：一个用于依赖声明中使用的完整版本字符串（例如
  `carpet_full_version=1.17.1-1.4.57+v220119`），另一个用于 `fabric.mod.json` 中使用的简化版本字符串（例如
  `carpet_version=1.4.57`）
- 在 `common.gradle` 的 `dependencies` 部分，插入相应的 `modImplementation` 或 `include` 声明。（例如
  `modImplementation "carpet:fabric-carpet:$carpet_full_version"`）
- 在 `common.gradle` 的 `processResources` 部分，找到以 `filesMatching('fabric.mod.json')` 开头的行，并插入一个键值对，如下所示：
  `filesMatching('fabric.mod.json') { expand ..., carpet_version: carpet_version, ... }`

如果你希望GitHub的 `release` 操作自动发布到Modrinth，请确保设置环境变量 `MODRINTH_ID` 和密钥 `MODRINTH_TOKEN`。如果你有模组依赖，请在
`.github/workflows/release.yml` 的 `dependencies`
字段中声明它们。有关声明语法，请参考 [mc-publish](https://github.com/Kira-NT/mc-publish) 。

有关preprocessor的更多信息，包括其确切用法、目的和机制，请参考 [ReplayMod的仓库](https://github.com/ReplayMod/preprocessor) 。

有关Fallen_Breath所做更改的更多信息，请参考 [他的仓库](https://github.com/Fallen-Breath/preprocessor) 。

## 许可证

本模板在GPL-3.0许可证下提供。
