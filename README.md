# ChromaAtlas（色彩图集）

给**没设 `mapColor` 的模组方块**自动补上地图颜色。不改外观、不改存档、不分发贴图。

小地图（Xaero）、FTB Chunks、Bluemap、原版地图读的都是方块的 `mapColor`。
很多装饰模组用 `Properties.of()` 建方块时没写 `.mapColor(...)` ⇒ 默认 `MapColor.NONE` ⇒ 地图上一片灰黑。

## 基本信息

| 项目 | 内容 |
|------|------|
| Mod ID | `chromaatlas` |
| MC 版本 | 1.20.1 / 1.21.1 |
| 平台 | Fabric / NeoForge |
| 依赖 | Fabric API |
| 协议 | LGPL-3.0 |

## 用法

装上去就生效，无需配置。默认**只补 `MapColor.NONE`** 的方块，不覆盖模组作者自己设的颜色。

| 命令 | 说明 |
|------|------|
| `/chromaatlas status` | 策略与写入统计 |
| `/chromaatlas dump` | 某方块当前颜色及其来源 |
| `/chromaatlas reload` | 重读配置与色表 |
| `/chromaatlas repaint [near\|all]` | 重绘已存地图（老地图不会自己变色） |

`/mapcolor` 是别名。

## 配置

`config/chromaatlas/chromaatlas.json`，首次启动自动生成。常用项：

- `onlyFillNone`（默认 `true`）：只补无色的方块
- `dryRun`：只记日志不生效
- `enabledNamespaces` / `disabledNamespaces`：按命名空间开关
- `runtimeFallback`：表里没有时后台从 mod jar 现算（结果进 `cache.json`）

`overrides.json` 里的手工值永远优先。

## 分支

仓库按 **MC 版本 / 加载器** 分分支，每条分支只放一个工程的代码（不再有 `fabric-1.20.1/` 这种版本目录）。

| 分支 | 内容 | JDK | Gradle | Loom |
|---|---|---|---|---|
| `1.20.1-fabric`（默认） | 1.20.1 Fabric 单工程 | 17 | 8.3 | 1.3.7 |
| `1.21.1-fabric/neoforge` | 1.21.1 Fabric + NeoForge 多工程（`common/` `fabric/` `neoforge/`） | 21 | 8.14 | 1.10.5 / 1.11.458 |
| `main` | 只读历史（旧的单目录多版本布局，tag `legacy-monorepo`） | — | — | — |

拉自己需要的版本：

```bash
git clone -b 1.20.1-fabric git@github.com:AkiFish3/ChromaAtlas.git
```

## 构建

```bash
./gradlew build
# 产物：build/libs/chromaatlas-<version>.jar
```

JDK 路径别写进仓库，放 `~/.gradle/gradle.properties` 的 `org.gradle.java.home`。
core 自检：`./gradlew selfTest`。

## 状态

| 阶段 | 状态 |
|---|---|
| 1.20.1 Fabric | ✅ 全量 29851 方块：`NONE` 4662（15.6%）→ 补 4586（98.4%），`verify 200/200` |
| 地图重绘 | ✅ 与原版逐字节一致 100.00%（16117/16117） |
| 1.21.1 Fabric | ✅ 构建绿，服务端 + 客户端 `verify 122/122`、0 异常 |
| 1.21.1 NeoForge | ✅ 构建绿，dev 服务端 `verify 122/122`、0 异常 |

## 边界

- 只写颜色数值，不分发任何贴图。
- 原版只有 61 个可用 `MapColor`，细微色差靠 `overrides.json` 兜。
- 老地图需要 `/chromaatlas repaint`；Xaero / FTB Chunks / Bluemap 有各自的缓存，需各自重扫。

## 鸣谢

本模组在制作过程中使用了 **DeepSeek-V4.1-Flash-1017** 与 **WorkBuddy** 进行 AI 辅助开发。

## 加入我们的服务器

服务器审核QQ群：**1065844449**

## 协议

LGPL-3.0 License —— 见 [LICENSE](LICENSE)。
