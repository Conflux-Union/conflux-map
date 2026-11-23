---
title: 参与开发
---

# 参与开发

## 从源码构建

构建需要 **JDK 21** 或更高版本。Minecraft、映射表、Fabric API 以及 26.x 构建所需的 JDK 25 工具链均由 Gradle 按需自动下载。

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

- 将 `1.21.11` 替换为[支持的版本表](../getting-started/install.md#支持的版本)中的任意版本即可构建对应节点；
- 构建产物分别输出至 `versions/<版本>/build/libs/` 与 `paper/build/libs/`；
- `:paper:runServer` 会下载本地 Paper 1.21.1 开发服务器并加载构建好的插件（首次运行需在 `paper/run/eula.txt` 中同意 EULA 后重新执行）。

## 模组开发者

Conflux Map 提供客户端公共 API（路径点、地图数据查询、自定义地图标记、动作与事件）。API 目前处于实验阶段，单个依赖即可覆盖所有受支持的 Minecraft 版本。接入方式与用法见 [公共 API](../technical/api.md)。

## 文档预览与配置参考

本 Wiki 使用 MkDocs 构建，本地实时预览命令如下：

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-docs.txt
.venv/bin/mkdocs serve
```

!!! warning "严格链接检查"
    CI 会执行 `mkdocs build --strict`，任何断链都会导致构建失败。

[配置参考](../config/index.md)页面由模组语言文件自动生成。修改配置标签或提示后，请执行以下命令重新生成并提交中英文两个页面：

```sh
python tools/gen_config_ref.py
```

翻译与本地化请遵循 [i18n 术语表](../technical/i18n-glossary.md)。

---

相关页面：[网络协议](../technical/protocol.md) · [预测质量](../technical/prediction-quality.md)
