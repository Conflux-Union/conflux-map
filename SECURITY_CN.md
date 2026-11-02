# 安全策略

[English](SECURITY.md) | **简体中文**

> 如中英文版本存在歧义，以英文版本为准。

## 支持的版本

安全修复只落在最新发布线上。如果你报告的问题得到修复，请升级到最新版本。

| 版本 | 支持情况 |
| ---- | -------- |
| [最新发布](https://github.com/Conflux-Union/conflux-map/releases) | ✅ |
| 旧版本 | ❌ —— 请先升级 |

## 报告漏洞

安全问题请不要公开开 issue，请通过以下任一私密渠道报告：

- **推荐：** 使用仓库 [Security 标签页](https://github.com/Conflux-Union/conflux-map/security) 的 "Report a vulnerability" 按钮。
- 或发送邮件到 [contact@cxu.org.cn](mailto:contact@cxu.org.cn)，标题注明 `conflux-map security`。

尽量附上：受影响的版本、复现步骤（世界种子、配置、相关日志或抓包）以及你观察到的影响。中英文均可。我们力争在 14 天内回复。

报告确认后，我们会准备修复并发布，同时发布 GitHub 安全公告并致谢——除非你希望匿名。

## 范围

- **在范围内：** 本仓库的客户端 mod、Fabric 服务端伴随组件、Paper 插件、随包发布的 cubiomes 原生二进制，以及本仓库的 CI 工作流。
- **不在范围内：** Minecraft 原版漏洞、由其他 mod 引起的崩溃或利用、已经公开的报告。
