---
title: Chest loot preview
---

# Chest loot preview

Once the world seed is available, right-clicking a structure marker on the fullscreen map (or using the map pane in Structure Search) adds a **Loot** entry to preview the items inside each chest.

## What Is Shown

Selecting **Loot** displays each chest in a vanilla container-style view, including:

- The chest's **block coordinates** and **loot table**;
- Predicted **item stacks**: item types, counts, enchantments and levels, plus potion/stew effects where present.

!!! info "How It Works"
    The preview computes initial vanilla chest contents **locally on the client** from the world seed (without player luck or context modifiers), so it works even for unexplored chunks.

## Supported Structures

Loot preview covers vanilla structures whose chests are placed at fixed, seed-derivable positions:

| Supported Structures | Not Supported |
|---|---|
| Desert Pyramid, Jungle Temple, Igloo, Shipwreck | Villages, Ocean Ruins, Woodland Mansions |
| Pillager Outpost, Ruined Portal (Nether variants from 1.18.2+), Buried Treasure | Ancient Cities, Mineshafts, Trial Chambers, Monster Rooms |
| Nether Fortress, Bastion Remnant, End City, Stronghold, Abandoned Camp (`26.3`) | — |

!!! warning "Datapack Limitation"
    Only vanilla loot tables are supported. Loot tables modified by datapacks are not predicted; unsimulatable chests still display their block coordinates and loot table name.

---

See also: [Structure & biome search](structure-biome-search.md) · [Autofill & prediction](autofill-prediction.md)
