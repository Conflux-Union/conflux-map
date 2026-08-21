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

Resource changes apply after the normal Minecraft resource reload (`F3+T`); a client restart is not
required.

## Xaero compatibility

An enabled Xaero UI resource pack can be retained unchanged when migrating. Conflux recognizes:

```text
assets/xaerobetterpvp/gui/minimap_frame.png
assets/xaeroworldmap/gui/gui.png
```

If Xaero's Minimap or World Map is also installed, the bundled default atlas alone does not reskin
Conflux. The compatibility path activates only when another resource-pack layer overrides that
atlas. If Xaero is not installed, the resource-pack asset itself is enough.

The minimap renderer uses Xaero's default square-frame regions (`x=192` corners, `y=0/16`
horizontal edges, and `y=97` vertical edges). Circular maps use the current frame strip at
`0,210 137x4`. These coordinates were verified against Xaero's Minimap 26.4.2; a future Xaero atlas
layout change can require a compatibility update. The world-map adapter uses only controls with an
unambiguous Conflux equivalent:

| Conflux resource | Xaero `gui.png` region |
| --- | --- |
| `group_waypoints.png` | `213,0 16x16` |
| `waypoint_manage.png` | `213,0 16x16` |
| `waypoint_local.png` | `229,48 16x16` |
| `waypoint_local_off.png` | `213,48 16x16` |
| `map_export.png` | `133,0 16x16` |
| `map_settings.png` | `113,0 20x20` |
| `world_profile.png` | `197,80 16x16` |

Conflux-specific controls such as annotations, structure search, biome display, and shared
waypoints deliberately keep their Conflux textures. Guessing an unrelated Xaero sprite would make
the control misleading. Xaero entity-icon definitions are a separate format and are not part of
this UI compatibility contract. The world-map coordinates above were verified against Xaero's
World Map 1.44.2.
