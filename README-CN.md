# Conflux Map

[English](README.md) | **简体中文**

Conflux Map 是一个 Fabric 客户端小地图 / 世界地图模组。它基于洁室（clean-room）
行为规格实现（见 [`docs/reference-specs/`](docs/reference-specs/README.md)），
而非基于任何现有模组的源码。本项目与 **VoxelMap**、**Xaero's Minimap/World
Map** 均无关联、非其背书、也不是从它们衍生而来——具体它们在多大程度上被参考、又
在哪些方面完全没有被使用，见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 功能特性（截至里程碑 M1）

M1 是纯客户端核心功能，以下所有功能对任意服务端（原版或模组端）均可独立工作，
不需要任何服务端组件配合。

- **小地图 HUD** —— 常驻屏幕角落的浮层，方形或圆形，4 个角落位置、4 档尺寸、
  4 档缩放，可选跟随视角旋转，坐标与生物群系信息行。
- **全屏世界地图** —— 可拖动缩放的探索地图，多分辨率瓦片，以光标为中心缩放，
  右键点击可直接创建路径点。
- **洞穴 / 下界 / 末地图层** —— 主世界地下模式自动检测（带滞回，边界处不会来回
  闪烁）、下界当前层/基岩顶层/手动 Y 切片三种模式、末地虚空背景渲染。
- **路径点与死亡点** —— 创建、编辑、上色、分组、显示/隐藏切换路径点；死亡自动
  生成死亡点；主世界与下界之间正确的 8:1 坐标换算；小地图边缘方向指示器提示
  范围外的路径点。
- **实体雷达** —— 敌对 / 被动 / 玩家 / 其他四类实体独立开关，可配置范围与实体
  数量上限。
- **磁盘缓存** —— 已探索地形按世界/服务器、维度、图层分别持久化到磁盘，重新
  进入世界时已探索区域立即显示，无需重新扫描。
- **游戏内设置界面** —— 上述所有设置项均可在游戏内直接调整，改动立即生效、
  无需重启（默认按键见下表）。
- **完整的中英文本地化。**

里程碑 M2 为全屏地图新增了基于种子预测的底图和可选服务端校正：依托内置的原生
[cubiomes](https://github.com/Cubitect/cubiomes) 库（`cn.net.rms.confluxmap.nativepredict`）
和纯逻辑预测代码（`cn.net.rms.confluxmap.core.predict`），单人游戏中把地图拖动到
未探索的主世界或末地区域时，会立即显示基于种子推算的生物群系、地形高度和合成
树冠纹理，真实已探索的瓦片会在其上层正常覆盖显示。多人游戏在服务端明确允许时才
共享种子；服务端只发送区块摘要差异，修正结果会按世界和维度持久化。

## 按键绑定

以下所有按键均可在 Minecraft 原版的按键设置界面中重新绑定，分类名为
"Conflux Map"。

| 默认按键 | 功能 |
|---|---|
| `H` | 开关小地图 |
| `]` | 小地图放大 |
| `[` | 小地图缩小 |
| `M` | 打开全屏世界地图 |
| `Y` | 切换手动图层模式 |
| `U` | 打开路径点列表 |
| `B` | 在当前位置新建路径点 |
| `,` | 打开设置界面 |
| `P` | 切换种子预览显示范围 |

## 构建

需要与 Minecraft 1.17.1 工具链兼容的 JDK（Java 16）；其余依赖（Minecraft、
映射表、Fabric Loader）Loom 会自动下载。

```sh
./gradlew :1.17.1:build
```

构建产物输出到 `versions/1.17.1/build/libs/`。M1 仅面向 Minecraft 1.17.1；
后续里程碑会按照 [`docs/reference-specs/README.md`](docs/reference-specs/README.md)
及本仓库实施计划中描述的、由 preprocessor 驱动的多版本布局，在 `versions/`
下加入更多版本。

## 许可证

Conflux Map 基于 **GNU General Public License v3.0** 发布——见
[`LICENSE`](LICENSE)。第三方组件与行为参考来源记录在
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 中。
