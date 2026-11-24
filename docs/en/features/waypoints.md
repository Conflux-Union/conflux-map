---
title: Waypoints
---

# Waypoints

Waypoints mark important coordinates in the world and on the map. Press `B` to create one at your current position, `U` to open the list, or `J` to toggle local waypoints.

## Core Features

- **Sets & Management**: Names, colors, and sets; the list supports search, filtering, and batch moving.
- **Death Points**: Automatically recorded per dimension (5 by default, adjustable from `0` to `50`).
- **In-World Rendering**: Shown as beams, labels, and distances in the world; off-screen waypoints appear as screen-edge indicators with a configurable distance limit.

## Cross-Dimension & Same-Seed Sharing

- **Overworld ↔ Nether 1:8 Ratio**: Overworld and Nether waypoints are shown in both dimensions using the `1:8` portal coordinate ratio by default (configurable in settings); End waypoints appear only in the End.
- **Same-Seed Worlds**: On servers with multiple sub-worlds sharing one seed, enable "Show Same-Seed World Waypoints" in settings to display waypoints across those worlds (off by default).

## Sharing & One-Click Migration

- **Chat Coordinate Sharing**: Preview coordinates in both Conflux Map and Xaero formats before sending, and import coordinates shared by other players in one click (no server companion required).
- **One-Click Import**: Import waypoints from **Xaero's Minimap** and **VoxelMap** in one click; duplicates are skipped and original files remain untouched.

## Waypoint enhancements (26.3)

The 26.3 enhancements add inline favorite stars, custom notes, preset tags, and searchable resource products including modded items. The waypoint screen opens the native Waypoints settings category. The native list is the default; enable Compact waypoint list in Waypoint enhancements to change only the list area, retaining header and footer positions. The compact grid combines local/public sources and sorts favorites before source preference and distance. Every enhancement has a switch, including favorites, notes/tags/search, photos, each preview location, compact/combined display, hover actions, source priority, quick publish, browsing memory, the temporary-point hotkey, and unknown-height fallback. Disabling a feature preserves its data. When automatic hover actions are off, right-click a card to open its actions. Its hover panel provides the native edit, visibility, confirmed deletion, share, locate, teleport, and photo actions. Quick publish creates a public copy while preserving the local original and enforcing server permissions and duplicate checks. Browsing state is remembered per world, without restoring selections or pending confirmations.

For a saved waypoint, open **Notes & Tags → Photos** to import or drag in PNG/JPEG files, edit captions, reorder, remove, and zoom. Image operations save immediately, independently of the note draft. Limits are 64 photos per waypoint, 32 MiB and 40 million pixels per image. Owned copies stay in `confluxmap/waypoint-media` on this device, including photos of public waypoints; they are never uploaded. Removal unlinks the photo while retaining its owned copy. External originals are never modified. Compact and fullscreen-map hover panels preview the first image. A new, initially unbound temporary-waypoint hotkey places a memory-only waypoint at the player's position. With MaliLib, its legacy binding is imported only when the native action has no stored entry; explicit native bindings, including an empty binding, are preserved.

Legacy halfmasa CM settings and list memory are imported once from `config/halfmasa`, preserving the original files and subsequent native preferences. Disable halfmasa's CM extension master switch before using the port to avoid duplicate handling.

---

See also: [Shared waypoints](shared-waypoints.md) · [Portal markers](portal-markers.md) · [Keybinds](../reference/keybinds.md)
