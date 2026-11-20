---
title: 参与开发
---

# 构建

需要 JDK 21 或更高版本。Minecraft、映射表、Fabric API,以及 26.x 构建所需的
JDK 25 工具链均由 Gradle 按需下载。

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

`1.21.11` 可替换为
[支持的版本表](../getting-started/install.md#支持的版本)中的任意版本。
构建产物分别输出至 `versions/<版本>/build/libs/` 与 `paper/build/libs/`。
`:paper:runServer` 会下载本地 Paper 1.21.1 开发服务器并安装刚构建的插件;
首次运行需在 `paper/run/eula.txt` 中接受 EULA,然后重新执行该任务。

# 模组开发者

Conflux Map 提供客户端 API(路径点、地图数据查询、自定义地图标记、动作与
事件)。API 目前为实验性,单个依赖即可覆盖所有受支持的 Minecraft 版本。
接入方式与用法见[公共 API](../technical/api.md)。

# 文档预览与配置参考

本 wiki 使用 MkDocs 构建。本地预览:

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-docs.txt
.venv/bin/mkdocs serve
```

CI 运行 `mkdocs build --strict`,断链会使构建失败。
[配置参考](../config/index.md)页面由模组语言文件生成——修改配置标签或提示后,
重新生成:

```sh
python tools/gen_config_ref.py
```

并提交两个生成的页面。翻译请遵循
[i18n 术语表](../technical/i18n-glossary.md)。

参见:[网络协议](../technical/protocol.md)、
[预测质量](../technical/prediction-quality.md)
