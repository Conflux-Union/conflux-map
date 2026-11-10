# Conflux Map 贡献须知

[English](CONTRIBUTING.md) | **简体中文**

感谢你愿意为 Conflux Map 做贡献！本指南覆盖贡献中的实操部分：跑通构建、改动会按哪些规则评审，以及如何提交 Pull Request。

## 参与方式

- **Bug 与兼容性报告**——用 issue 模板提交；模板会引导你给出排查所需的信息，提供中英文两个版本。
- **功能建议**——请先开 issue 讨论方案，再动手写代码。
- **代码修复与功能开发**——见下文。
- **翻译**——语言文件位于 `src/main/resources/assets/confluxmap/lang/`（目前为 `en_us.json` 与 `zh_cn.json`）；面向玩家的措辞请与 [`docs/i18n-glossary.md`](docs/i18n-glossary.md) 保持一致。

非平凡改动请先开 issue，每个 pull request 只聚焦一个 issue 或一个改动。

## 环境搭建

需要 Git 和 JDK 21 或更高版本。Minecraft、映射表、Fabric API，以及 26.x 构建所需的 JDK 25 工具链均由 Gradle 按需下载。

```sh
git clone --recursive https://github.com/Conflux-Union/conflux-map
cd conflux-map
./gradlew :1.17.1:build
```

克隆必须带 `--recursive`：`native/cubiomes` 是 git 子模块。如果已经克隆但缺少子模块，执行 `git submodule update --init`。

## 仓库布局

| 路径 | 内容 |
| --- | --- |
| `common/` | 与 Minecraft 解耦的领域代码：地图存储、瓦片合成、路径点、网络协议。纯 Java 16。 |
| `src/` | Fabric 客户端与服务端实现，以 Minecraft 1.17.1 为基准编写，经预处理器生成所有其他版本。 |
| `api/` | 实验性客户端 API 模块（见 [`docs/api.md`](docs/api.md)）。 |
| `paper/` | 独立的 Paper 插件。 |
| `versions/` | 每个受支持的 Minecraft 版本一个 Gradle 子工程。`versions/mainProject` 指明源版本；其余版本全部由 `src/` 生成。 |
| `native/` | cubiomes git 子模块与预编译的预测二进制。 |
| `docs/` | 开发者文档：API 指南、Paper 插件说明、翻译词汇表。 |

`1.17.1` 是源版本工程；把下述命令中的版本号换成任意受支持版本，即可基于同一份源码构建或测试该版本。

## 常用命令

```sh
./gradlew :1.17.1:build        # 打包 mod jar（不跑验证）
./gradlew :1.17.1:test         # 共享模块与 Fabric 测试
./gradlew :common:test         # 仅核心测试
./gradlew :paper:check         # 验证 Paper 插件
./gradlew :1.17.1:check        # 单个版本的完整验证
./gradlew :1.17.1:runClient    # 开发客户端
./gradlew :1.17.1:runServer    # 开发服务端
```

说明：

- 仓库没有格式化或 lint 任务；请与周围代码的风格保持一致。
- `build` 不会编译 `src/gametest`；它在 Minecraft 1.21.4 及以上版本由 `check` 编译并运行。重命名了与 gametest 共享的类型后，用 `./gradlew :1.21.4:compileGametestJava` 快速验证。

## 评审规则

1. **`common/` 保持与 Minecraft 解耦。** 不得出现 Minecraft、Fabric、Bukkit、mixin、LWJGL 导入，也不得使用预处理器指令。平台访问属于 `src/main`（Fabric）或 `paper/`。
2. **预处理器指令只属于 `src/main`。** 其余版本工程由它生成；不要编辑 `versions/*/build/` 下的任何内容。
3. **一份源码，多个版本。** 对 `src/main` 的改动会同时发布到所有受支持版本。请构建并测试你的改动可能影响的版本，尤其是涉及版本门控代码时。
4. **`common/` 与 `api/` 按 Java 16 编译**——不要在那里使用更新的语言特性。
5. **Mixins 在 `src/main/resources/confluxmap.mixins.json` 中统一注册**，构建时按版本展开（只替换 `java_version` 占位符）；没有需要同步的按版本副本。
6. **`native/cubiomes` 是子模块。** 不要原地修改；改动通过 fork 提交并更新子模块指针。
7. **代码、注释与提交信息一律使用英文。**
8. **Markdown 只进既定文档。** 面向用户的行为写在 `README.md`/`README_CN.md`（由维护者把关）；开发者契约与流程写在 `docs/` 现有文件中（如 `docs/reference-specs/`）。不要为测量报告、验证记录、基准总结或构建/二进制溯源新增 `.md` 文件——这类证据应放进 PR 描述、issue 或测试。`docs/performance/` 与 `native/prebuilt/BUILD_INFO.md` 是有意删除的，不要恢复。

## 测试

让验证与改动范围相匹配：

- 一般改动：`./gradlew :common:test`，加上你触及版本的测试。
- Paper 改动：`./gradlew :paper:check`。
- 地图同步、预测或 native 相关改动：CI 会在 PR 上跑完整版本矩阵；本地尽量测试你能测的部分。

native 集成测试在平台库无法初始化时会自动跳过——在不常见平台上跳过是预期行为，不算失败。行为变更应通过现有的公共入口附带针对性测试。

## Pull Request

- 在 PR 描述中关联 issue（例如 `Fixes #123`）。
- PR 标题遵循 [Conventional Commits](https://www.conventionalcommits.org/) 风格，与现有提交历史一致，例如 `fix(core): …`。
- CI 会在每个 PR 上构建并测试整个版本矩阵，必须通过。
- PR 以 squash 方式合并，因此 PR 分支内部的提交历史无需刻意打磨。

## 获取帮助

需要讨论的内容请开 issue，或通过 [README_CN](README_CN.md) 中的联系方式找到维护者（邮箱与 QQ 群；中英文皆可）。

## 许可证

Conflux Map 采用 [GPL-3.0](LICENSE)。参与贡献即表示你同意你的贡献同样以 GPL-3.0 授权。任何引入或参考的第三方代码都必须记录在 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 中。
