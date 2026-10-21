# ChromaAtlas

Automatically fills in map colors for **modded blocks that never set `mapColor`**.
It doesn't change how blocks look, doesn't touch your saves, and never redistributes textures.

Minimaps (Xaero), FTB Chunks, Bluemap / server maps and vanilla maps all read `mapColor`.
Plenty of decoration mods build blocks with `Properties.of()` and never call `.mapColor(...)`
⇒ the block stays `MapColor.NONE` ⇒ the map is a grey smear.

## Info

| Item | Value |
|------|-------|
| Mod ID | `chromaatlas` |
| MC version | 1.20.1 / 1.21.1 |
| Platform | Fabric (NeoForge in progress) |
| Requires | Fabric API |
| License | LGPL-3.0 |

## How it works

1. **Offline**: walk each block model's parent chain, take `elements` / `textures` (preferring
   `textures.particle`), average the pixels while skipping fully transparent ones, then snap the
   result to the closest vanilla `MapColor` by CIELAB ΔE → `mapcolors.json`.
2. **Runtime**: consume that table only. A mixin takes over
   `BlockBehaviour$BlockStateBase#getMapColor` and returns the override when present.
3. Only blocks currently at `MapColor.NONE` are filled by default; `overrides.json` always wins;
   `dryRun` logs what would happen without applying it.

No resource scanning, no network, no jar walking at boot — startup cost is reading one json
(30986 entries, **19–22 ms**).

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/chromaatlas status` | 2 | Strategy, apply counters, repaint state |
| `/chromaatlas dump` | 2 | Current mapColor of a block and where it came from |
| `/chromaatlas list` | 2 | Blocks this mod rewrote |
| `/chromaatlas reload` | 2 | Reload config + table without restarting |
| `/chromaatlas repaint [near <r>\|all\|stop]` | 2 | Repaint existing maps (old maps stay grey otherwise) |

`/mapcolor` is an alias.

## Config

`config/chromaatlas/chromaatlas.json`, generated on first launch:

| Key | Default | Meaning |
|-----|---------|---------|
| `strategy` | `fill-none` | `fill-none` / `fill-missing-model` / `all` |
| `onlyFillNone` | `true` | Only fill blocks that are `MapColor.NONE` |
| `dryRun` | `false` | Log the plan, apply nothing |
| `runtimeFallback` | `true` | For missing entries, compute from mod jars on a low-priority thread → `cache.json` |
| `enabledNamespaces` / `disabledNamespaces` | empty | Per-namespace allow / deny |
| `repaintPolicy` | `off` | `off` / `near` / `all` — auto repaint after boot |
| `repaintPixelsPerTick` | `4096` | Repaint budget (measured 0.447 µs/pixel ⇒ ≈1.8 ms/tick) |
| `repaintRadiusChunks` | `8` | Radius for the `near` policy |

Storage priority is fixed: `overrides.json` (human) > `cache.json` (runtime fallback) >
`chromaatlas/mapcolors.json` inside the jar (shipped snapshot) > computed on the spot.
Mutable data always lives in `config/`, never written back into the jar.

## Building

Each loader shell builds independently; `core/` is added to the main source set via `sourceSets`,
so all shells compile the same code.

| Shell | JDK | Gradle | Loom |
|-------|-----|--------|------|
| `fabric-1.20.1` | 17 | 8.3 | 1.3.7 |
| `fabric-1.21.1` | 21 | 8.14 | 1.10.5 |
| `neoforge-1.21.1` | 21 | 8.14 | architectury-loom |

```bash
cd fabric-1.20.1 && ./gradlew build
# output: build/libs/chromaatlas-<version>.jar
```

Keep the JDK path out of the repo — put `org.gradle.java.home=/path/to/jdk` in
`~/.gradle/gradle.properties`.

The 1.21.1 Fabric shell is **generated from the 1.20.1 shell** by `_mc6_shell_gen.py`
(idempotent, self-checked with `--check`) rather than maintained by hand.

> On this setup loom 1.8.x silently emits a 261 B empty jar under Gradle 8.14 (the real one lands
> in `build/devlibs/`), so `fabric-1.21.1` pins loom 1.10.5. Always verify artifacts by
> `devlibs` size + `libs` entry count, not by the BUILD SUCCESSFUL line.

Core self-test (no Gradle, no network):

```bash
cd core
javac -encoding UTF-8 --release 17 -d ../build/core $(find src/main/java -name '*.java')
javac -encoding UTF-8 --release 17 -cp ../build/core -d ../build/core $(find src/test/java -name '*.java')
java -cp ../build/core com.akira.chromaatlas.core.SelfTest
```

## Status

| Stage | State |
|-------|-------|
| M0 scaffold + core | ✅ |
| M1 false-positive triage | ✅ 37 "never called" jars: 33 confirmed / 4 need runtime checks |
| M2 runtime probe | ✅ 29851 blocks / `NONE` **4662 (15.62%)** / 95 namespaces affected |
| M3 / M3b table + runtime fallback | ✅ **4586 (98.37%)** fillable / **76 (0.25%)** left, none with static textures |
| M4 apply layer | ✅ 4586 applied under `fill-none`, `verify 200/200`, 1e6 lookups in 10–11 ms |
| M4b map repaint | ✅ **100.00%** byte-identical to vanilla (16117/16117), 0 bytes changed in unloaded chunks |
| M5 full acceptance | 🟡 quantified: median ΔE **8.6** / P90 15.7, 0 wrong edits |
| M6a 1.21.1 Fabric | ✅ build green; `verify 122/122` on both server and client, 0 exceptions |
| M6b 1.21.1 NeoForge | 🚧 in progress |
| M7 / M8 | ⬜ |

## Limits & known deviations

- Writes color values only — **no textures are redistributed**.
- Vanilla has just 61 usable `MapColor` values; fine-grained differences go through `overrides.json`.
- Changing textures with a resource pack will shift colors (the offline table was built from the originals).
- **Old maps don't update by themselves**: `MapState.colors` is never recomputed once painted, so run
  `/chromaatlas repaint`. Xaero / FTB Chunks / Bluemap keep their own caches and need their own rescan.
- Dynamic / derived models (copycats and friends) yield no static color and are skipped as "no data".

## Credits

Built with AI assistance from **DeepSeek-V4.1-Flash-1017** and **WorkBuddy**.

## Our server

Server review QQ group: **1065844449**

## License

LGPL-3.0 — see [LICENSE](LICENSE).
