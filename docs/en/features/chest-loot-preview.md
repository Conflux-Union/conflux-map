---
title: Chest loot preview
---

# Chest loot preview

Once the world seed is available, the location menu of a structure marker on
the fullscreen map gains a Loot entry for supported structures; the structure
search screen's map pane hosts the same menu. Choosing Loot previews every
chest inside the structure with its block position, loot table, and the
predicted item stacks — each stack's item, count, enchantments with levels,
and the effect when one is present. The preview is the same deterministic
vanilla loot roll the chest made at generation, computed client-side from
the world seed: it ignores player context and works on unexplored chunks.

Loot previews exist for the structures whose chests the prediction can
enumerate — Desert Pyramid, Jungle Temple, Igloo, Shipwreck, Pillager
Outpost, Ruined Portal (Nether variant on 1.18.2 and newer), Buried
Treasure, Nether Fortress, Bastion Remnant, End City, Stronghold, and
Abandoned Camp on 26.3 — while structures such as Villages, Ocean Ruins,
Woodland Mansions, Ancient Cities, Mineshafts, Trial Chambers, and monster
rooms show no Loot entry. Only vanilla loot tables can be predicted: tables
modified by a datapack are outside the preview, and a chest whose table
cannot roll still shows its position and loot table.

See also: [Structure & biome search](structure-biome-search.md),
[Autofill & prediction](autofill-prediction.md)
