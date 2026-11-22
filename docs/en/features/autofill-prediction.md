---
title: Autofill & prediction
---

# Autofill & prediction

Once the world seed is available, the Overworld, Nether roof, and End
immediately display predicted terrain. Exploration progressively replaces
the prediction with the real map, and a server with the companion installed
also sends real-terrain corrections. Press `P` to cycle everywhere /
generated-only / explored-only, and `F9` to force a refresh. Predictions default to
the AUTO backend, which measures the GPU against the CPU pool once per
machine and serves the faster one (integrated graphics land on the CPU);
the backend is selectable in the map autofill settings. A local
seed can also be configured to produce a prediction map visible only to the
local player.

See also: [World map](world-map.md),
[Structure & biome search](structure-biome-search.md),
[Prediction quality](../technical/prediction-quality.md)
