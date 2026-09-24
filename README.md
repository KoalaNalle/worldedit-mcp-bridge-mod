# WorldEdit MCP bridge for NeoForge

This is the [KoalaNalle development fork](https://github.com/KoalaNalle/worldedit-mcp-bridge-mod)
of [Lucas-Buckley's original bridge](https://github.com/Lucas-Buckley/worldedit-mcp-bridge-mod),
based on upstream commit `7148342e976501a8a993558f1967ef2397c421d1`. The original
license and attribution remain in `LICENSE` and the Git history. It pairs with
[KoalaNalle/mcp-worldedit-craftscript](https://github.com/KoalaNalle/mcp-worldedit-craftscript).

## Purpose

WorldEdit's player-scoped commands need a real `ServerPlayer` actor. Minecraft's RCON
`execute as` substitutes a Brigadier source but does not make WorldEdit's own `//`
command pipeline see that player. The bridge adapts the connected player with
`NeoForgeAdapter.adaptPlayer` and dispatches a WorldEdit `CommandEvent` on the server
thread. It also reads selection state directly through WorldEdit's `LocalSession` API.

The bridge listens only on `127.0.0.1:25577` by default. `WEDIT_BRIDGE_PORT` can change
the port, not the bind address. This socket is an internal connection trusted by the
local MCP server. Do not publish it or RCON to the Internet.

## Local protocol

Each connection sends one UTF-8 JSON line, receives one JSON line, and closes. Requests
are limited to 64 KiB. The connection pool and queue are bounded, and WorldEdit access
runs on Minecraft's server thread with a 10-second response deadline.

Read a player's actual selection:

```json
{"action":"get_selection","username":"SomePlayer"}
```

```json
{"ok":true,"selection":{"dimension":"minecraft:overworld","selection_type":"cuboid","min":{"x":1,"y":64,"z":2},"max":{"x":3,"y":67,"z":4},"width":3,"height":4,"length":3,"block_volume":36}}
```

The dimension is the selection's own dimension, which can differ from the player's
current dimension. Width, height, length, and volume come from WorldEdit's selected
`Region`; volume is a 64-bit count. Missing, incomplete, offline, and invalid requests
return `ok:false`, `error_code`, and `error`.

## Durable construction projects

See [Durable builder protocol 2](DURABLE_BUILDER.md) for the authoritative project/phase model, exact BuildPlans, limits, conflicts, restart recovery and rollback. [Historical protocol 1](LEGACY_BOUNDED_PROTOCOL.md) remains documented for recovery of pre-existing operations only.

## Building

1. Use Java 21, Minecraft 1.21.1, and NeoForge 21.1.233 or newer.
2. Obtain the WorldEdit 7.3.8 NeoForge/Fabric `worldedit-mod-7.3.8.jar` from the
   [official release](https://modrinth.com/plugin/worldedit/version/7.3.8).
3. By default Gradle resolves the pinned official WorldEdit artifact from Modrinth Maven. Optionally copy `local.properties.example` to ignored `local.properties`; set
   `worldedit.jarPath` to the absolute jar path. The jar is a compile-only dependency.
4. Run `./gradlew.bat build` on Windows, or `./gradlew build` on other systems.
5. Install this mod and WorldEdit into the same NeoForge development server/client.
   Confirm the loopback listener message in the log.

Neither this fork nor the Minecraft: Dune project bundles WorldEdit. Minecraft: Dune
builds and runs independently of this development bridge.
