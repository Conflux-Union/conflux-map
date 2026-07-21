# Waypoint Set Controls Design QA

- source visual truth path: `C:\Users\30710\AppData\Local\Temp\codex-clipboard-f048f0db-ab95-408a-8fb5-d9903186a4d5.png`
- implementation screenshot path: unavailable; the Minecraft client screen was not launched in an interactive world session
- viewport: source screenshot 2048 x 395; implementation viewport unavailable
- state: local waypoint tab, empty selected set
- full-view comparison evidence: blocked because no rendered implementation capture is available
- focused region comparison evidence: blocked for the same reason; the relevant region is the two-row set filter and batch-move toolbar

**Findings**

- [P2] Rendered fidelity has not been visually verified.
  Location: waypoint-set filter and batch-move target controls.
  Evidence: the source shows oversized native buttons with missing center texture regions and seven equally prominent management actions. The implementation caps dropdown triggers at 200 pixels, places set management in the filter dropdown footer, and shows the batch-move row only after selection, but no matching in-game screenshot was captured.
  Impact: compile and interaction calculations are verified, but spacing, overlap, typography, colors, and popup layering still require an in-game check.
  Fix: launch Minecraft 1.17.1 with a local waypoint store, capture the local waypoint tab with a dropdown open at the same GUI scale, and compare it with the source.

**Required Fidelity Surfaces**

- Fonts and typography: existing Minecraft text renderer and translation strings are reused; rendered size, truncation, and optical alignment remain unverified.
- Spacing and layout rhythm: collection selection and Select All share the primary toolbar row; the contextual batch row appears only after selection; rendered spacing and overlay behavior remain unverified.
- Colors and visual tokens: existing native button rendering is retained; the selected set has a light accent and Delete Set uses a restrained danger red, but both remain visually unverified.
- Image quality and asset fidelity: no new raster, vector, icon, or decorative assets were introduced.
- Copy and content: existing localized set-filter and move-target labels are reused.

**Implementation Checklist**

- Capture the local waypoint screen with the filter dropdown closed and open.
- Verify wheel scrolling and scrollbar dragging with at least seven waypoint sets.
- Verify the move-target dropdown, contextual batch-row transition, outside-click close, and Escape close states.
- Verify the dropdown footer actions and destructive-action color hierarchy.
- Confirm no popup row is hidden behind waypoint-row buttons at narrow and wide GUI scales.

**Comparison History**

- Initial source inspection identified the missing center texture caused by oversized native buttons.
- The implementation replaced cycling behavior with bounded dropdown triggers and a scrollable popup, then reorganized actions into primary, secondary, contextual, and destructive levels following the UX audit.
- Post-fix visual evidence is unavailable, so no visual pass can be recorded.

final result: blocked

---

# In-world Waypoint HUD Design QA

- source visual truth path: `C:\Data\Project\conflux-map\design-references\hud-option1-targeted-revised.png`
- implementation screenshot path: unavailable; an interactive Minecraft world with a distant waypoint was not launched
- viewport: target concept 1536 x 1024; implementation viewport unavailable
- state: distant waypoint idle icon, targeted expanded details, and interrupted collapse transition
- full-view comparison evidence: blocked because no rendered implementation capture is available
- focused region comparison evidence: blocked for the same reason; the relevant region is the icon anchored to the waypoint beam and its right-expanding detail panel

**Findings**

- [P2] Rendered HUD fidelity has not been visually verified.
  Location: in-world waypoint marker above the beam.
  Evidence: the implementation follows the selected pixel nameplate direction with a single colored initial icon, dark detail panel, name, and distance, but no matching in-game screenshot was captured.
  Impact: targeting and motion math are unit-tested and the mod compiles, while apparent size, panel direction, font sharpness, and depth interaction still need an in-game check.
  Fix: enter a world with waypoints at roughly 50 m, 500 m, and 2,000 m; capture idle, fully targeted, and mid-collapse frames at the same GUI scale as the target comparison.

**Required Fidelity Surfaces**

- Fonts and typography: the existing Minecraft text renderer is reused for the icon initial, waypoint name, and meter value.
- Spacing and layout rhythm: the icon remains on one anchor; its 12-pixel collapsed plate grows to 18 pixels while the 20-pixel-high panel expands only from the icon's right edge.
- Colors and visual tokens: waypoint fill colors are retained; local, shared, and locked outlines reuse the existing dark, cyan, and amber marker semantics.
- Motion: target acquisition expands over 140 ms; target loss collapses over 110 ms and reverses from the current progress without snapping.
- Image quality and asset fidelity: the runtime HUD uses existing Minecraft primitives and text rendering; no new runtime texture asset is introduced.
- Content: idle state shows only the initial; targeted state shows the full waypoint name and rounded distance in meters.

**Implementation Checklist**

- Capture idle markers at near, medium, and far distances and confirm their apparent size remains readable.
- Confirm the crosshair selects only the best-aligned waypoint when markers overlap.
- Move the crosshair on and off repeatedly during expansion and collapse; verify there is no jump or duplicate icon.
- Confirm the panel visually expands to the right and retracts toward the icon.
- Verify local, shared, and locked outlines against bright sky, foliage, darkness, and terrain occlusion.
- Stand behind a solid wall and confirm the icon, expanded panel, and text remain fully visible while the beam keeps its world-space occlusion.
- Verify the existing vertical light beam is visually unchanged.

**Comparison History**

- The original HUD rendered a permanently visible two-line label whose distance scaling capped too early, making distant information nearly unreadable.
- Three visual directions were explored; the selected pixel nameplate was revised to one fixed icon with a right-side details panel.
- The implementation now separates glance state from intent state and adds reversible motion, but post-build visual evidence is unavailable, so no visual pass can be recorded.

final result: blocked
