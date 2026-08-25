# UI resource packs

Conflux Map exposes its bitmap UI through normal Minecraft client resource packs. Packs do not
change widget positions, screen structure, text layout, or interaction behavior; they replace the
textures rendered inside those controls.

## Native resources

Toolbar icons use independent 64x64 alpha-mask textures under:

```text
assets/confluxmap/textures/gui/*.png
```

The shipped files in that directory are the complete supported icon list. Keep their paths and
dimensions unchanged. The icon RGB channels should be white because Conflux applies the disabled,
selected, and hovered colors at render time. Matching `.png.mcmeta` files can enable blur and clamp
sampling.

A pack can also add either of these optional transparent frame overlays:

```text
assets/confluxmap/textures/gui/minimap_frame_square.png
assets/confluxmap/textures/gui/minimap_frame_circle.png
```

Each frame is sampled over the complete minimap square. Transparent pixels reveal the map below.
The image can use any square resolution, although a power-of-two texture is recommended. A native
Conflux frame or icon always takes priority over the Xaero compatibility mapping below.

A pack can replace the code-drawn `^` player marker on both the minimap and full-screen map with:

```text
assets/confluxmap/textures/gui/player_marker.png
```

The complete square texture is rendered at 16x16 GUI pixels and rotated around its center. Artwork
must face up in the source image; up represents the player's forward direction. The texture is
full-color and should use transparency around the marker. If the resource is absent, Conflux draws
an enabled resource pack's Xaero player-arrow region when available, then falls back to the style
selected under **Settings > Minimap > Player Marker**. `New` is the default outlined `^`;
`Traditional` restores the filled triangle. When either resource-pack marker is present, the
setting displays `Resource Pack` and is disabled until the override is removed and resources are
reloaded.

Resource changes apply after the normal Minecraft resource reload (`F3+T`); a client restart is not
required.

## Vanilla control styles

Conflux keeps its built-in dark button style when Minecraft is using only its base UI resources.
If the active resource stack overrides Minecraft's normal button texture, Conflux automatically
uses the effective normal, highlighted, and disabled button textures for its text buttons, map
toolbar buttons, and color-swatch frames. This is based only on the resource stack, never on a
resource-pack name:

```text
Minecraft 1.20.2+: assets/minecraft/textures/gui/sprites/widget/button.png
Older versions:    assets/minecraft/textures/gui/widgets.png
```

The two choices are intentionally independent: a pack can restyle vanilla controls without
providing Xaero assets, and a Xaero-compatible pack can replace map icons without changing vanilla
buttons. Selected map tools retain a thin semantic outline because vanilla buttons do not expose a
persistent selected state.

## Xaero compatibility

An enabled Xaero UI resource pack can be retained unchanged when migrating. Conflux recognizes:

```text
assets/xaerobetterpvp/gui/minimap_frame.png
assets/xaerobetterpvp/gui/guis.png
assets/xaeroworldmap/gui/gui.png
```

If Xaero's Minimap or World Map is also installed, the bundled default atlas alone does not reskin
Conflux. The compatibility path activates only when another resource-pack layer overrides that
atlas. If Xaero is not installed, the resource-pack asset itself is enough.

The minimap renderer uses Xaero's default square-frame regions (`x=192` corners, `y=0/16`
horizontal edges, and `y=97` vertical edges). Circular maps use the current frame strip at
`0,210 137x4`. These coordinates were verified against Xaero's Minimap 26.4.2; a future Xaero atlas
layout change can require a compatibility update.

The player marker uses Xaero's `guis.png` arrow at `49,0 26x28`, rendered at Xaero's current
default scale and rotated around the same off-center pivot. The source sprite is a white mask, so
Conflux applies its normal minimap/full-screen marker color and Xaero-style dark vertical outline.
Conflux compensates for the source sprite facing down. Xaero's own arrow color, opacity, and scale
settings are not imported. A native `assets/confluxmap/textures/gui/player_marker.png` always wins
when both marker resources are present.

World-map icon compatibility is based on the control fields and tooltips in Xaero's World Map
1.44.2, not on visual similarity. Xaero atlas icons are treated as full-color artwork; Conflux does
not apply the selected-state RGB mask intended for its native white icons.

### Audited icon catalog

Every shipped Conflux GUI icon has an explicit interoperability decision. `Native` means that
Xaero has no control with the same scope and action, so the Conflux texture remains in use. A pack
can still replace it by adding the normal `assets/confluxmap/textures/gui/<file>` resource.

| Conflux resource | Xaero control/region | Decision |
| --- | --- | --- |
| `group_waypoints.png` | `waypointsButton`, `213,0 16x16` | Exact: toggles the waypoint tool/menu group |
| `waypoint_manage.png` | `waypointsButton`, `213,0 16x16` | Category match: waypoint management instead of Xaero's quick menu |
| `map_export.png` | `exportButton`, `133,0 16x16` | Exact: exports the map as PNG |
| `map_settings.png` | `settingsButton`, `113,0 20x20` | Exact: opens map settings |
| `group_view.png` | None | Native: Xaero has no grouped display-mode control |
| `group_actions.png` | None | Native: Xaero has no matching action group |
| `waypoint_local.png` | None | Native: Xaero's toggle controls all waypoint sets, not local waypoints |
| `waypoint_local_off.png` | None | Native: Xaero's toggle controls all waypoint sets, not local waypoints |
| `waypoint_shared.png` | None | Native: Xaero has no server-shared waypoint visibility set |
| `waypoint_shared_off.png` | None | Native: Xaero has no server-shared waypoint visibility set |
| `world_profile.png` | None | Native: Xaero's dimension toggle does not select client world profiles |
| `structure_search.png` | None | Native: Xaero has no structure-search control |
| `structure_search_off.png` | None | Native: Xaero has no structure-search control |
| `map_terrain.png` | None | Native: terrain is not a Xaero toolbar display-mode control |
| `chunk_load_state.png` | None | Native: Xaero has no chunk-load-state layer |
| `chunk_load_state_off.png` | None | Native: Xaero has no chunk-load-state layer |
| `map_biome.png` | None | Native: Xaero has no equivalent biome display-mode toggle |
| `map_biome_off.png` | None | Native: Xaero has no equivalent biome display-mode toggle |
| `annotation_collapse.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_drawing.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_select.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_eraser.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_line.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_circle.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_rectangle.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_freehand.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_label.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_persistence.png` | None | Native: Xaero has no annotation persistence mode |
| `annotation_persistence_transient.png` | None | Native: Xaero has no annotation persistence mode |
| `annotation_undo.png` | None | Native: Xaero has no annotation toolbar |
| `annotation_redo.png` | None | Native: Xaero has no annotation toolbar |

In particular, Xaero's `renderWaypointsButton` regions at `229,48` and `213,48` are deliberately
not used for Conflux's local-waypoint switch. That Xaero control toggles every waypoint set, which
is a different scope. Xaero entity-icon definitions are also a separate format and are not part of
this UI compatibility contract.
