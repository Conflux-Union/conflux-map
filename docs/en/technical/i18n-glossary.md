# Player-facing language guide

Use the same player-facing term for the same feature throughout every screen. Describe what players can do or what happened; do not expose implementation details such as caches, snapshots, GPU resources, debounce logic, native libraries, protocol state, or persistence internals.

## Core terms

| Concept | English | Simplified Chinese |
| --- | --- | --- |
| Product name | Conflux Map | Conflux 地图 |
| Waypoint | Waypoint | 路径点 |
| Local waypoint | Local Waypoint | 本地路径点 |
| Shared waypoint | Shared Waypoint | 共享路径点 |
| Share (action) | Share | 分享 |
| Seed-based map fill-in | Map Autofill | 地图补全 |
| Waypoint set | Waypoint Set | 路径点集 |
| Possible structure location | Possible Location | 可能位置 |
| Per-server sub-world profile | Sub-world | 子世界 |

Use `Shared Waypoint` for the server-visible feature even when an internal key or identifier contains `public`. Use `Map Autofill` for the player feature even when source code refers to prediction.

## Style

- Use title case for short English button and setting labels, and sentence case for explanatory text and status messages.
- In Chinese, add a space between Chinese text and Latin letters or numbers when they form separate words. Do not insert spaces between Chinese words.
- Format coordinates as `X: 12, Y: 64, Z: -30` in every locale.
- Prefer action and outcome language, such as `Refresh Map Autofill` or `地图更新失败`.
- Replace implementation-specific failures with a useful player-level result, such as `Shared waypoints are not available right now.`
