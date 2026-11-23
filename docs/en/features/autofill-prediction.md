---
title: Autofill & prediction
---

# Autofill & prediction

Map autofill predicts unexplored terrain locally on the client from the world seed.

## How It Works

- **Covered Dimensions**: Once the world seed is available, the **Overworld, Nether roof, and End** immediately display predicted terrain.
- **Live Replacement & Server Corrections**: Exploration progressively replaces predictions with the real map; on servers with the [server companion](../technical/server-companion.md) installed, the server also sends real-terrain corrections.
- **Local Seed**: When a server does not share the seed, you can configure a **local seed** in settings to generate a prediction map visible only to you.

## Performance & Controls

- **Compute Backend**: Predictions default to the **AUTO** backend, which measures the GPU against the CPU pool once per machine and serves the faster one (integrated graphics land on the CPU); the backend can be selected in map autofill settings.
- **Keybinds**:
  - Press `P` to cycle **everywhere / generated-only / explored-only**.
  - Press `F9` to force a tile refresh.

!!! note "Server Permissions"
    Multiplayer servers can control seed sharing via `shareSeed` or disable the prediction underlay for cooperating clients via `allowPrediction` in `config/confluxmap/server.json`.

---

See also: [World map](world-map.md) · [Structure & biome search](structure-biome-search.md) · [Prediction quality](../technical/prediction-quality.md)
