# MaliLib config-screen API integration

Research date: 2026-07-27. Updated 2026-08-02 for runtime capability detection.

## Question

How should a downstream mod expose its MaliLib-managed hotkeys from MaliLib's
configuration UI, and should it add a separate key to Minecraft's vanilla
Controls screen?

## Primary-source findings

- MaliLib's maintainer documents `A + C` (or Mod Menu) as the normal way to
  enter MaliLib's configuration UI.
- Modern MaliLib exposes `Registry.CONFIG_SCREEN`. Downstream mods register a
  `ModInfo` containing their mod ID, display name, and a supplier for their
  `GuiBase` config screen through `registerConfigScreenFactory(...)`.
- `GuiConfigsBase` builds the top-right mod switcher from that registry and
  switches to the selected screen supplier. It also attempts a fallback
  auto-registration based on a screen's `modId`, but explicit registration is
  required to give a downstream screen the correct display name before it is
  first opened.
- `ConfigPanelAllHotkeys` is MaliLib's global all-hotkeys screen. It uses
  MaliLib's own mod ID and combines every registered category, so subclassing it
  makes the page identify itself as MaliLib instead of Conflux Map.
- `GuiModConfigs` is the appropriate base for a downstream page containing only
  that mod's config objects. Its stock layout reserves 70 GUI units at the
  bottom and uses a 204-unit config control width.

## Version boundary verified in this repository

The exact compile-only MaliLib jars configured by each active Gradle subproject
were inspected with `javap`:

- Minecraft 1.21.1 through 26.1.2 provide `Registry.CONFIG_SCREEN`,
  `ConfigScreenRegistry.registerConfigScreenFactory(ModInfo)`, the three-argument
  `ModInfo` constructor, and the `GuiModConfigs` list constructor.
- The repository's pinned Minecraft 1.17.1 MaliLib provides `GuiModConfigs` but
  not the config-screen registry. A compatible runtime build can nevertheless
  backport the later registry and switcher API under the same class and method
  names.

Therefore Conflux Map registers a dedicated config screen in the A+C switcher
directly on 1.21.1 and newer. On 1.17.1 it detects the registry at runtime and
registers reflectively when the installed MaliLib exposes it. The vanilla
compatibility shortcut is retained only when that registration API is absent
or incompatible, preserving support for the pinned legacy dependency.

## Sources

- [MaliLib maintainer on the A+C config entry](https://github.com/maruohon/tweakeroo/issues/399#issuecomment-1407979313)
- [ConfigScreenRegistry in MaliLib 26.1.2-0.28.8](https://github.com/sakura-ryoko/malilib/blob/26.1.2-0.28.8/src/main/java/fi/dy/masa/malilib/gui/config/registry/ConfigScreenRegistry.java)
- [GuiConfigsBase config-switcher implementation](https://github.com/sakura-ryoko/malilib/blob/26.1.2-0.28.8/src/main/java/fi/dy/masa/malilib/gui/GuiConfigsBase.java)
- [ConfigPanelAllHotkeys global page](https://github.com/sakura-ryoko/malilib/blob/26.1.2-0.28.8/src/main/java/fi/dy/masa/malilib/config/gui/ConfigPanelAllHotkeys.java)
- [GuiModConfigs downstream config-list base](https://github.com/sakura-ryoko/malilib/blob/26.1.2-0.28.8/src/main/java/fi/dy/masa/malilib/config/gui/GuiModConfigs.java)
