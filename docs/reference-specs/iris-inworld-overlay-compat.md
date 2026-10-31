# In-World Overlay Rendering Under Iris (mechanism reference)

How Iris captures world-pass rendering, why raw mid-pass draws vanish under
deferred shader packs, and how established minimap mods keep in-world waypoint
overlays visible anyway. This is a mechanism-level compatibility note, not an
implementation spec: unlike the other documents in this directory it cites
third-party class/method names, because the point is to pin down the exact
behavior of Iris and the reference mods, not to copy them.

Sources examined (2026-10-30):

- Iris source, branches `26.1` (default), `26.3`, `1.21.9`, and `1.20.1`
  (github.com/IrisShaders/Iris, shallow clones). Released Iris builds exist for
  1.21.9/1.21.10 (1.9.7), 1.21.11 (1.10.8), and 26.1–26.3 (1.11.4–1.11.7) per
  the Modrinth API.
- Fabric API `fabric-rendering-v1`, branch `1.21.11`
  (github.com/FabricMC/fabric), for the positions of the reworked world render
  events.
- Xaero's Minimap, Fabric builds 26.6.2 (MC 26.3) and 26.6.0 (MC 1.20.1),
  decompiled for reading; behavior described in our own words.
- JourneyMap legacy source (LGPL, github.com/TeamJM/journeymap-legacy,
  1.7.10-era, copyright 2011–2017 Techbrew). Current JourneyMap is closed
  source (github.com/TeamJM/journeymap is an issue tracker only), so nothing
  about its modern hook can be cited from code.

## 1. The Iris capture rule

All Iris branches we checked (1.20.1, 1.21.9, 26.1, 26.3) implement the same
skeleton; only the hooked vanilla method signatures differ:

1. **World-pass window.** `MixinLevelRenderer.iris$setupPipeline` at
   `LevelRenderer.renderLevel` HEAD calls `pipeline.beginLevelRendering()`,
   which sets `isRenderingWorld = true`
   (`common/.../iris/pipeline/IrisRenderingPipeline.java`, `beginLevelRendering`;
   `common/.../iris/mixin/MixinLevelRenderer.java`). Every Iris branch keeps
   `shouldOverrideShaders() { return isRenderingWorld && isMainBound; }`
   (`IrisRenderingPipeline.java`, near the end). The window opens at
   `renderLevel` HEAD and closes inside `finalizeLevelRendering()`.

2. **Program override.** While that window is open,
   `MixinShaderManager_Overrides` (a mixin on `GlDevice.getOrCompilePipeline`)
   redirects every `RenderPipeline` an Iris pack shader instead of the vanilla
   program. This is unconditional over the whole window: entity immediate
   buffers, terrain, block entities, debug renderers, and anything a mod draws
   through normal pipelines mid-pass all get redirected.

3. **Gbuffer bind at program setup.** Each redirected program is an
   `ExtendedShader` (or `FallbackShader`) holding two `GlFramebuffer`s,
   `writingToBeforeTranslucent` / `writingToAfterTranslucent`. Its setup binds
   the matching gbuffer framebuffer (`ExtendedShader.java`, `iris$setupState`;
   identical code on 1.20.1). The before/after-translucent split is how the
   pack's `depthtex1`/`depthtex2` copies get made
   (`IrisRenderingPipeline.beginTranslucents` / `beginHand`).

   **This is the capture mechanism**: a draw is "captured" if and only if it
   goes through the device pipeline machinery (any vanilla `RenderType` /
   `RenderPipeline`, via `MultiBufferSource`, submit collectors, or queues)
   while the world-pass window is open. It then lands in the pack's gbuffers,
   gets shaded by the pack (fog, bloom, per-phase programs), and survives into
   the final image as part of the scene.

4. **Raw draws are not captured.** Draws that bypass pipelines — plain
   Tessellator/GL calls — never trigger the override or the gbuffer bind. They
   write into whatever framebuffer happens to be bound at that moment, which
   mid-pass is an Iris gbuffer or an intermediate target, not the composite
   result.

5. **Finalize erases the main color attachment.**
   `pipeline.finalizeLevelRendering()` runs
   `compositeRenderer.renderAll()` + `finalPassRenderer.renderFinalPass()`
   (`IrisRenderingPipeline.finalizeLevelRendering`); the final pass renders
   into the vanilla main render target (`FinalPassRenderer.java`, final-pass
   `createRenderPass` on the main color texture). Deferred packs rebuild the
   whole color image from the gbuffers here, so anything written to the main
   framebuffer mid-pass — i.e. the raw draws from (4) — is overwritten.

6. **Finalize runs before RETURN-time mod draws.** `iris$endLevelRender`
   deliberately ends the pipeline early, with the comment: *"Inject a bit early
   so that we can end our rendering before mods like VoxelMap (which inject at
   RETURN) render their waypoint beams."*
   - 26.1 / 26.3 and 1.21.9 branches: injected at
     `Matrix4fStack.popMatrix()` inside `renderLevel`
     (`MixinLevelRenderer.iris$endLevelRender`).
   - 1.20.1 branch: injected at `RETURN` with `shift = Shift.BEFORE`
     (`src/main/java/.../mixin/MixinLevelRenderer.java`), which sorts it ahead
     of plain RETURN handlers.

   After finalize, `isRenderingWorld` is false, overrides are off, and the
   main framebuffer holds the finished frame — so draws issued at RETURN (or
   later in the frame) land on top of it and are visible. Iris's own comment
   names this exact pattern as the supported way for waypoint mods to draw.

Iris offers no official extension point for in-world overlays; its public API
v0 (`common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java`) only
exposes `isShaderPackInUse()` / `isRenderingShadowPass()` for detection. The
irisshaders.dev site's docs target shader-pack authors, not mod authors, and
the repository's `docs/` folder likewise has no rendering-compatibility guide;
the operative mod-facing guidance is in Fabric's own event javadoc (below).

## 2. Safe vs erased draws, by version line

**Classic lines (≤ 1.21.4-generation Fabric `WorldRenderEvents`):**
`AFTER_TRANSLUCENT`, `LAST`, etc. fire inside `renderLevel`, so:

- raw GL draws there → erased by composite/final;
- draws through RenderTypes → captured, shaded by the pack;
- `WorldRenderEvents.END` fires at `renderLevel`'s RETURN, after Iris's
  finalize → raw or RenderType draws both land on the finished frame.

One caveat about END-time depth testing: occlusion at END depends on the main
target's depth attachment still holding scene depth after the pack's passes,
which is pack behavior, not a contract. Vanilla-only builds do keep it.

**1.21.9+ / 26.x lines (Fabric events re-added inside the rewritten
renderer):** Fabric API 1.21.11 fires `WorldRenderEvents.START_MAIN`,
`BEFORE_ENTITIES`, `AFTER_ENTITIES`, `BEFORE_DEBUG_RENDER`,
`BEFORE_TRANSLUCENT`, `BEFORE_BLOCK_OUTLINE`, and `END_MAIN` (plus
extraction-time events) from injections
into `renderLevel` / the main-pass method
(`fabric-rendering-v1/.../mixin/client/rendering/LevelRendererMixin.java`;
`BEFORE_TRANSLUCENT` at the `translucent` profiler push, `END_MAIN` at the
last `endBatch()`). All of them are inside the world-pass window, so the
capture rule of section 1 applies verbatim: in-pass events are captured
(RenderTypes) or erased (raw GL); no Fabric world event fires after Iris's
finalize on these lines. Fabric's javadoc for the terrain events says exactly
this: third-party renderers "may combine these passes or otherwise alter the
rendering pipeline ... This can break direct writes to the framebuffer ...
use an existing terrain RenderLayer instead of outputting to the framebuffer
directly with GL calls" (`fabric-rendering-v1/.../world/WorldRenderEvents.java`,
`BEFORE_ENTITIES` javadoc).

**The GUI/HUD phase is the universal safe zone.** GUI rendering runs after
`renderLevel` returns — after composite and final — and Iris does not capture
it (`shouldOverrideShaders()` is false there). Anything drawn in the HUD phase
reaches the screen unchanged, with or without a pack.

## 3. Xaero's Minimap: HUD-phase billboards (behavioral spec)

Xaero draws **no world-pass geometry for waypoints at all** on either version
examined (26.6.2 on MC 26.3, 26.6.0 on MC 1.20.1); identical architecture:

- **Matrix capture, draw nothing.** A mixin at `LevelRenderer.render` HEAD
  (`xaero.common.mixin.MixinOptionalLevelRenderer` →
  `XaeroMinimapCore.onWorldModelViewMatrix`, capturing the camera rotation
  view matrix from the render state) and a `@ModifyArg` on
  `GameRenderer.renderLevel`'s projection-buffer call
  (`MixinGameRenderer` → `XaeroMinimapCore.onProjectionMatrix`) copy the two
  world matrices into statics (`waypointsProjection`, `waypointModelView`)
  each frame. That is the entire world-pass footprint.
- **Draw in the GUI phase.** `MixinGuiRenderer` injects at `GuiRenderer.render`
  HEAD/RETURN (1.20.1: `MixinFabricInGameHud` at `InGameHud.render`'s first
  game-mode check), invoking `handleRenderGameOverlayEventPre`. The handler
  saves the current RenderSystem projection, installs an ortho projection and
  identity model-view, draws, then restores everything
  (`xaero.common.events.ClientEvents.handleRenderGameOverlayEventPre`).
- **CPU projection to screen space.** For each waypoint
  (`xaero.hud.minimap.element.render.world.MinimapElementWorldRendererHandler.
  transformAndRenderForRenderer`): compute the camera-relative offset; cull
  with a dot product against the camera forward vector (skip if behind,
  depth < 0.05); clamp extreme distances (250k); multiply the cached view and
  projection matrices through a 4-component vector and convert clip space to
  rounded screen X/Y (`(1 + x/w)/2 · width`, `(1 - y/w)/2 · height`); then
  translate the GUI pose stack by that screen offset and draw the icon, label,
  and optional colored background as ordinary 2D GUI geometry.
- **Consequences.** Waypoint overlays are always-on-top (no world depth test);
  occlusion/behind-camera handling is CPU math; appearance is identical with
  and without a shader pack, because nothing is drawn while Iris is capturing.
  The price is that labels are flat screen-space elements — they scale by a
  distance formula, not true perspective, and terrain never hides them.
- **Shader detection exists but is inert for waypoints.** `SupportMods` does
  `Class.forName("net.irisshaders.iris.api.v0.IrisApi")` (and
  `optifine.Patcher`) to set support flags; the Iris flag is only used by a
  debug-quality offscreen entity-model renderer (`EntityRenderTracer`), which
  unwraps `net.coderbot.iris.layer.IrisRenderTypeWrapper` so Iris-wrapped
  layers do not confuse that offscreen pass. The waypoint path does not branch
  on any shader state.
- **No beams.** Xaero's in-world presence is billboard icon + label only; it
  has no world-pass beam feature that could be erased, which is why it never
  needed the RETURN escape hatch.

## 4. JourneyMap: end-of-world-pass raw draw (legacy donor)

The last open-source JourneyMap (`TeamJM/journeymap-legacy`, 1.7.10-era) is
the classic pattern:

- Hook: Forge `RenderWorldLastEvent`
  (`journeymap.client.forge.event.WaypointBeaconHandler`), i.e. after the
  world pass returns — the Forge equivalent of Fabric's END.
- Draw: `journeymap.client.render.ingame.RenderWaypointBeacon` renders
  static/rotating beams from vanilla `textures/entity/beacon_beam.png` with
  raw GL11 matrix pushes, camera-relative translation, and Tessellator quads,
  with distance fade and render-distance clamping done on the CPU.
- Shader handling: none. The only "shaders" references in the module are
  defensive `glClearColor` resets for its offscreen map rendering. It works
  under Iris-era packs for the same structural reason our END hook does: the
  event fires after Iris's finalize.

Current JourneyMap is closed source (ARR; the GitHub repo is an issue
tracker), so its modern 1.21.9+/26.x hook cannot be cited from code.

## 5. Implications for Conflux Map

There are exactly two robust mechanisms, plus the classic escape hatch:

- **A. Captured path.** Emit geometry through vanilla RenderTypes via the
  level's immediate source / submit collectors during the world pass. Under a
  pack the geometry is shaded as part of the scene (survives composite; can
  receive fog/bloom; sorted with translucents); without a pack it renders
  normally. Risks: the pack's gbuffer program for the chosen RenderType
  imposes its own blending/fog, so a custom additive look may shift; depth
  sorting with translucent terrain applies.
- **B. HUD-phase billboard path (Xaero's).** Cache the world matrices at
  world-render start, CPU-project each waypoint to screen coordinates, draw in
  the GUI phase. Never erased, identical with/without packs, but no world
  depth occlusion and no true perspective scaling.
- **C. RETURN-time draw (classic escape hatch).** Draw after Iris's finalize.
  On classic lines Fabric's END already fires there. On 1.21.9+/26.x no Fabric
  event does, but Iris still finalizes before `renderLevel`'s tail, so a small
  own mixin at `renderLevel` RETURN (or at the GUI render entry, Xaero-style)
  reproduces it.

Per branch (`WaypointWorldRenderer.register()` and the beam painter):

- **MC < 12109:** beam and labels at `WorldRenderEvents.END` (RETURN) —
  mechanism C, already correct. JourneyMap-legacy-equivalent. Residual risk:
  END-time depth occlusion of the beam is pack-dependent (section 2 caveat);
  if a pack shows beams through terrain or clips them oddly, that is why.
- **12109–12110 (own `WorldRendererMixin`) and 12111
  (`BEFORE_TRANSLUCENT`):** the beam is raw Tessellator inside the world-pass
  window → erased under packs, while labels (queue/`END_MAIN`, captured
  RenderType paths) survive → expect "labels visible, beams gone" under packs.
  Mechanism A fits the beam here: the column is world geometry, so submitting
  it through a vanilla RenderType (as the labels already are through the
  command queue) keeps it alive and shaded. Mechanism C (own RETURN mixin) is
  the alternative if the always-on-top look must be preserved.
- **26.1/26.2 (`AFTER_TRANSLUCENT_TERRAIN` beam, raw):** same erasure under
  packs. 26.2 already has the submit machinery (labels ride
  `COLLECT_SUBMITS`); submitting the beam the way 26.3 does is the natural
  fix (mechanism A).
- **26.3:** the beam is already a captured path
  (`COLLECT_SUBMITS` → `FabricOrderedSubmitNodeCollector.submitCustom` with
  `RenderTypes.dragonRays()`), so under Iris it is overridden into a pack
  gbuffer program and should survive as shaded scene geometry; labels likewise
  submitted. This needs on-screen verification against an actual Iris 26.3
  build, but mechanically it is the correct category.
- **Javadoc corrections.** Two statements in the current
  `WaypointWorldRenderer` javadoc are outdated: (1) "no Iris dev environment
  exists for those lines yet" — Iris ships released builds and maintained
  branches for 1.21.9 through 26.3, all with the same
  finalize-before-RETURN structure, so mechanism C is available on those lines
  via our own mixin; (2) the implied framing that packs erase "anything drawn
  inside the world pass" is too coarse — packs erase *uncaptured* (raw) draws
  and the main color attachment; RenderType/submitted draws inside the pass
  are shaded into the final image, which is the mechanism our 26.3 beam path
  already rides.
