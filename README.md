# ChromaAtlas（色图集）

给**没设 `mapColor` 的模组方块**自动补上地图颜色。不改方块外观、不改存档、不分发任何贴图。

小地图（Xaero）、FTB Chunks、Bluemap / 服务端地图、原版地图读的都是方块的 `mapColor`。
大量装饰类模组用 `Properties.of()` 建方块时没写 `.mapColor(...)` ⇒ 默认 `MapColor.NONE` ⇒ 地图上整片灰黑。

## 基本信息

| 项目 | 内容 |
|------|------|
| Mod ID | `chromaatlas` |
| MC 版本 | 1.20.1 / 1.21.1 |
| 平台 | Fabric（NeoForge 在研） |
| 依赖 | Fabric API |
| 协议 | LGPL-3.0 |

## 怎么做的

1. **离线**从方块模型的贴图算平均 RGB（沿 parent 链取 `elements` / `textures`，优先 `textures.particle`，
   跳过全透明像素），再按 CIELAB ΔE 吸附到原版 `MapColor` 可用色 ⇒ `mapcolors.json`。
2. **运行时**只消费这份表：mixin 接管 `BlockBehaviour$BlockStateBase#getMapColor`，命中即返回覆盖色。
3. 默认**只补 `MapColor.NONE`**；`overrides.json` 手工值永远优先；支持 `dryRun` 只记日志不生效。

不解析资源、不联网、启动时也不开 jar —— 启动开销就是读一份 json（30986 条 **19~22 ms**）。

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/chromaatlas status` | 2 | 运行策略、写入统计、重绘状态 |
| `/chromaatlas dump` | 2 | 看某个方块当前生效的 mapColor 与来源 |
| `/chromaatlas list` | 2 | 列出本 mod 改写了哪些方块 |
| `/chromaatlas reload` | 2 | 重读配置与色表（不重启） |
| `/chromaatlas repaint [near <r>\|all\|stop]` | 2 | 重绘已存地图（老地图不重绘永远是灰的） |

`/mapcolor` 是别名。

## 配置

`config/chromaatlas/chromaatlas.json`（首次启动自动生成）：

| 键 | 默认 | 说明 |
|---|---|---|
| `strategy` | `fill-none` | `fill-none` / `fill-missing-model` / `all` |
| `onlyFillNone` | `true` | 只补当前是 `MapColor.NONE` 的方块（不覆盖模组作者的选择） |
| `dryRun` | `false` | 只记录「本来会写多少条」，不生效 |
| `runtimeFallback` | `true` | 表里没有时用后台线程从 mod jar 现算（结果进 `cache.json`） |
| `enabledNamespaces` / `disabledNamespaces` | 空 | 按命名空间白/黑名单 |
| `repaintPolicy` | `off` | `off` / `near` / `all` —— 启动后是否自动重绘 |
| `repaintPixelsPerTick` | `4096` | 重绘预算（实测 0.447 µs/像素 ⇒ 4096/tick ≈ 1.8 ms） |
| `repaintRadiusChunks` | `8` | `near` 策略的半径 |

存储分层（写死的优先级）：`overrides.json`（人）> `cache.json`（运行时兜底）> jar 内 `chromaatlas/mapcolors.json`（出厂快照）> 现场算。
**可变数据一律进 config，不写回 jar** —— 玩家装了新模组后 jar 里的表就过期了。

## 构建

各壳独立构建，core 通过 `sourceSets` 并进主 source set（同一份代码三壳共用）。

| 壳 | JDK | Gradle | Loom |
|---|---|---|---|
| `fabric-1.20.1` | 17 | 8.3 | 1.3.7 |
| `fabric-1.21.1` | 21 | 8.14 | 1.10.5 |
| `neoforge-1.21.1` | 21 | 8.14 | architectury-loom |

```bash
cd fabric-1.20.1 && ./gradlew build
# 产物：build/libs/chromaatlas-<version>.jar
```

JDK 路径不要写进仓库，放用户级配置：`~/.gradle/gradle.properties` → `org.gradle.java.home=/path/to/jdk`。

1.21.1 的 fabric 壳由 `_mc6_shell_gen.py` **从 1.20.1 壳生成**（幂等，`--check` 自证），不手搬。

> loom 1.8.x 在本机 Gradle 8.14 下会静默产出 261 B 的空 jar（真正的包在 `build/devlibs/`），
> 已在 `fabric-1.21.1` 固定用 loom 1.10.5。验收一律看 `devlibs` 体积 + `libs` 的 entries 数，别看 BUILD SUCCESSFUL。

core 自检（不需要 gradle、不需要联网）：

```bash
cd core
javac -encoding UTF-8 --release 17 -d ../build/core $(find src/main/java -name '*.java')
javac -encoding UTF-8 --release 17 -cp ../build/core -d ../build/core $(find src/test/java -name '*.java')
java -cp ../build/core com.akira.chromaatlas.core.SelfTest
```

## 状态

| 模块 | 状态 |
|---|---|
| M0 脚手架 + core | ✅ |
| M1 假阳排除 | ✅ 37 个「零调用」jar：33 可信 / 4 需运行时复核 |
| M2 运行时探针 | ✅ 29851 方块 / `NONE` **4662（15.62%）** / 95 个命名空间有缺口 |
| M3 / M3b 色表生成 + 运行时兜底 | ✅ 表内可补 **4586（98.37%）** / 残余 **76（0.25%）**，全部无静态纹理 |
| M4 应用层 | ✅ fill-none 写入 4586、`verify 200/200`、热路径 1e6 次查询 10~11 ms |
| M4b 地图重绘 | ✅ 与原版逐字节一致 **100.00%**（16117/16117）、未加载区 0 字节变化 |
| M5 全量验收 | 🟡 量化口径：ΔE 中位 **8.6** / P90 15.7、误改 0 |
| M6a 1.21.1 Fabric | ✅ 构建绿；服务端 + 客户端 `verify 122/122`、0 异常 |
| M6b 1.21.1 NeoForge | 🚧 在研 |
| M7 / M8 | ⬜ |

## 边界与已知偏差

- 只写颜色数值，**不分发任何贴图**。
- 原版只有 61 个可用 `MapColor`，装饰方块的细微色差靠 `overrides.json` 兜。
- 资源包换了贴图，颜色会偏（离线表按原贴图算）。
- **老地图不会自动变色**：`MapState.colors` 画过就不再重算 ⇒ 需要 `/chromaatlas repaint`。
  Xaero / FTB Chunks / Bluemap 各有自己的缓存，不吃这套，需各自重扫。
- 派生属性 / 动态模型（如 copycats）算不出静态颜色，会被跳过并计入「无数据」。

## 鸣谢

本模组在制作过程中使用了 **DeepSeek-V4.1-Flash-1017** 与 **WorkBuddy** 进行 AI 辅助开发。

## 加入我们的服务器

服务器审核QQ群：**1065844449**

## 协议

LGPL-3.0 License —— 见 [LICENSE](LICENSE)。
