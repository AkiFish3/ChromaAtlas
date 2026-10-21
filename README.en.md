# ChromaAtlas

Automatically fills in map colors for **modded blocks that never set `mapColor`**.
It doesn't change how blocks look, doesn't touch saves, and never redistributes textures.

Minimaps (Xaero), FTB Chunks, Bluemap and vanilla maps all read `mapColor`.
Plenty of decoration mods build blocks with `Properties.of()` without `.mapColor(...)`
⇒ the block stays `MapColor.NONE` ⇒ the map is a grey smear.

## Info

| Item | Value |
|------|-------|
| Mod ID | `chromaatlas` |
| MC version | 1.20.1 / 1.21.1 |
| Platform | Fabric (NeoForge in progress) |
| Requires | Fabric API |
| License | LGPL-3.0 |

## Usage

Works out of the box. Only blocks currently at `MapColor.NONE` are filled, so colors
chosen by mod authors are never overridden.

| Command | Description |
|---------|-------------|
| `/chromaatlas status` | Strategy and apply counters |
| `/chromaatlas dump` | A block's current color and where it came from |
| `/chromaatlas reload` | Reload config and color table |
| `/chromaatlas repaint [near\|all]` | Repaint existing maps (old maps never update by themselves) |

`/mapcolor` is an alias.

## Config

`config/chromaatlas/chromaatlas.json`, generated on first launch. The common keys:

- `onlyFillNone` (default `true`) — fill only blocks without a color
- `dryRun` — log the plan, apply nothing
- `enabledNamespaces` / `disabledNamespaces` — per-namespace allow / deny
- `runtimeFallback` — compute missing entries from mod jars on a background thread (→ `cache.json`)

Values in `overrides.json` always win.

## Building

Each loader shell builds independently; `core/` is shared via `sourceSets`.

| Shell | JDK | Gradle | Loom |
|-------|-----|--------|------|
| `fabric-1.20.1` | 17 | 8.3 | 1.3.7 |
| `fabric-1.21.1` | 21 | 8.14 | 1.10.5 |

```bash
cd fabric-1.20.1 && ./gradlew build
# output: build/libs/chromaatlas-<version>.jar
```

Keep the JDK path out of the repo — put `org.gradle.java.home` in `~/.gradle/gradle.properties`.

## Status

| Stage | State |
|-------|-------|
| 1.20.1 Fabric | ✅ 29851 blocks: `NONE` 4662 (15.6%) → 4586 filled (98.4%), `verify 200/200` |
| Map repaint | ✅ 100.00% byte-identical to vanilla (16117/16117) |
| 1.21.1 Fabric | ✅ build green, `verify 122/122` on server and client, 0 exceptions |
| 1.21.1 NeoForge | 🚧 in progress |

## Limits

- Writes color values only — no textures are redistributed.
- Vanilla has just 61 usable `MapColor` values; fine differences go through `overrides.json`.
- Old maps need `/chromaatlas repaint`; Xaero / FTB Chunks / Bluemap keep their own caches and rescan separately.

## Credits

Built with AI assistance from **DeepSeek-V4.1-Flash-1017** and **WorkBuddy**.

## Our server

Server review QQ group: **1065844449**

## License

LGPL-3.0 — see [LICENSE](LICENSE).
