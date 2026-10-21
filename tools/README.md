# tools/

离线工具。目前脚本住在工作区 `C:/Users/Administrator/WorkBuddy/2026-09-14-20-38-30/`（那边已有扫描/校验资产），
稳定后再搬进来，避免两份副本漂移。

| 脚本 | 作用 | 产物 |
|---|---|---|
| `_mc1_mod_mapcolor_scan.py` | 全实例静态扫描：blockstate 数 vs 类里 mapColor 调用 | `_mc1_mod_mapcolor.tsv` |
| `_mc0_mapcolor_table.py` | javap 解析 MapColor `<clinit>` → 62 条 id/名/RGB，并生成 `VanillaMapColors.java` | `_mc0_mapcolor_table.tsv` |
| `_mc1_copy_check.py` | 常量池级假阳排除（`Properties.copy` / `FabricBlockSettings.copy`），`--selftest` 防假绿 | `_mc1_copy_check.tsv` / `_mc1_summary.tsv` |
| `_mc3_palette_build.py`（M3 待写） | 贴图平均色 → 61 色最近邻 | `mapcolors.json` / `_mc3_preview.png` |

Python 一律用 `C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe`。
