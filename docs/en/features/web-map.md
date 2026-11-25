---
title: Web map
---

# Web map

The server companion serves a built-in browser map at `127.0.0.1:8123` by default — see [Server companion](../technical/server-companion.md) for binding, public exposure, and privacy setup. Anyone with the address can browse explored terrain, predicted terrain, shared waypoints, and online players in a regular browser, with no mod installed.

## Browsing

- **Dimensions & Modes**: Switch dimension and choose which regions are served — all regions or generated regions only (the server can set the default for new visitors).
- **Center Coordinates**: The lower-left corner shows the current viewport center `X`/`Z`.
- **Player & Waypoint Search**: Jump the view to an online player or a public waypoint; the server can disable either search.
- **Web Chat**: An optional chat panel relays messages between browsers and the game. It is off by default, and the server controls game-chat relay and whether browsers may send.
- **Language**: The interface follows the browser language, with a manual switch.

## Multiple Servers on One Entry Point

When Fabric and Paper servers run on the same host, they can share one web map entry point. A **Switch server** dropdown lists every instance, and the game mode shown after each server name can be hidden.

## Players & Photos

- **Avatars**: Players appear with their skin face on the map and in the player list; when no skin is available, the default avatar is shown.
- **Public Waypoints**: Approved waypoint photos open with the public waypoint in the browser — see [Shared waypoints](shared-waypoints.md) for publishing and approval.
- **Player Privacy**: Each player controls their own visibility with `/confluxmap webmap hide` / `show` in game.

---

See also: [Shared waypoints](shared-waypoints.md) · [Server companion](../technical/server-companion.md) · [Multi-server data](multi-server.md)
