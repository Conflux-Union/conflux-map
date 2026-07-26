# TODO

Backlog distilled from a community feature-request list (collected 2026-07-24).
Only items whose scope is unambiguous are written up here; the rest are parked
under [Deferred](#deferred) until the requesters clarify what they actually want.

## 1. Nether bedrock-ceiling layer split

Merges three separate requests ("auto-ignore the bedrock layer in the Nether",
"record both the part above and the part below the bedrock layer", "layer
toggle with a keybind") — they describe one feature.

### Problem

The Nether has `height=256` but `logical_height=128`, so chunks only generate
content in y=0..127 and the bedrock ceiling caps that at y≈124-127. Everything
from y=128 up is empty buildable space, which players use for roof travel.

Both Nether layers currently stop on the top face of that ceiling:

- `NETHER_CURRENT` pivots on the player's eye Y and scans down for the nearest
  floor. Standing on the roof puts the pivot above y=127, so the first solid
  block found is the bedrock cap.
- `NETHER_CEILING` pivots on `world.getTopY() - 1` (=255) and scans down, which
  reaches the same bedrock cap.

Either way the map is a flat grey sheet while the player is on the roof.

### Wanted behavior

- A scan mode that treats bedrock as transparent, so the terrain *below* the
  cap (fortresses, bastions, lava sea) stays visible from the roof.
- Above-cap and below-cap views kept as two independently persisted layers, so
  switching does not discard either one and roof rail networks survive a
  reload.
- One keybind cycling the two states.

### What to change

1. `mc/snapshot/McChunkSnapshotFactory#sampleFloorColumn` — add a bedrock-skipping
   scan mode for the above-cap layer.
2. `core/model/MapLayer` — model "roof surface" and "below-cap body" as two
   distinct layers, each with its own `cacheId`, both `persistent`. Today
   `NETHER_CEILING` is `persistent=true` while `NETHER_CURRENT` is
   `persistent=false`, so the below-cap view never reaches disk.
3. `mc/world/LayerSelector` — pick the layer from whether the player is above or
   below the cap; update the Nether branch of `nextOverride` to match the new
   two-state semantics. The `cycle_layer` keybind (Y) already drives this and
   needs no new registration.
4. `core/cache/RegionCacheService` / `RegionDiskCache` — confirm the two new
   cache ids never share a region file.

## 2. Map image export

Export the current world-map view as a PNG.

Tile pixel data and `compat/NativeImages` already exist, so the work is mostly
writing the image out in chunks instead of materializing one huge buffer —
a full-detail export of a large explored area is far past a sane heap budget.

Open sub-decisions (implementation-side, not blocking): export at the on-screen
LOD versus always LOD 0, and whether the exported region is the viewport or a
user-picked rectangle.

## 3. Modifier-key binding system

Support modifier combinations (Ctrl/Alt/Shift + key) for this mod's keybinds,
comparable to what MaliLib offers. Implemented in-tree; no dependency on MaliLib.

All 11 keybinds currently go through vanilla `KeyBinding` in `mc/input/Keybinds`,
which is single-key only, and the defaults already occupy H, `[`, `]`, M, Y, U,
B, J, `,`, P and F9.

Build it as an optional overlay above vanilla `KeyBinding` rather than a
replacement, so the bindings stay visible in the vanilla controls screen and in
ModMenu.

## Deferred

Parked because the request does not yet pin down the behavior or the data
source. Not rejected — they need one answer each before they can be scoped.

| Request | Missing |
|---|---|
| Zoom out to 0.0625x | Already satisfied: the world map's `MIN_SCALE`/`MAX_SCALE` reach 16.0 blocks-per-pixel, which is 0.0625x, and that is exactly `TileMath.MAX_LOD`. Going further means raising `MAX_LOD`, which is a different change. |
| MiniHUD-linked biome colors *or* borders | Which of the two, and no reason to link against MiniHUD at all — biome data is already client-side, and `core/predict` has `BiomeTable`/`PredictionPalette`. |
| MiniHUD-linked multi-player load range | No client-side data source; needs a server-side plan through the `server/` companion first. |
| Chunk ticket levels | Same data-source problem, plus ticket levels expose other players' positions and AFK farms, so the companion permission model has to be settled before any rendering work. |
| Server chunk load state | Overlaps the two rows above; "load state" is not specified. |
| Drawing / annotation tools | Unclear whether this means drawing annotations on the map or something else. The image-export half of the same request became item 2. |
| Entity head icons shown by default | `ConfluxConfig#radarIconsEnabled` already defaults to `true`; unclear what is actually missing. |
