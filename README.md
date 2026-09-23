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
are limited to 8 KiB. The connection pool and queue are bounded, and WorldEdit access
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

## Bounded construction prototype

Block edits are **disabled by default**. For a disposable development world, set a
single allowed region in the server process environment before launch, for example:

```text
WEDIT_BRIDGE_ALLOWED_REGION=minecraft:overworld;3000,100,240;3007,107,247
```

The coordinates are inclusive. The bridge requires that the connected player is a
Minecraft operator, and only edits that player's current dimension inside this
region. The service rejects unloaded chunks rather
than generating them. Explicit block lists accept at most 64 unique writes.
Uniform cuboid fills and selections accept at most 512 cells; every operation is
still capped at 512 changed cells and a 512-cell bounding volume. Targets are limited to the default states of
`minecraft:stone_bricks`, `minecraft:sandstone`, `minecraft:smooth_sandstone`,
`minecraft:cut_sandstone`, and `minecraft:chiseled_sandstone`. Changed blocks must
be air beforehand, so this prototype cannot replace existing terrain or structures.
Block entities are never overwritten. One operation may be pending undo at a time. The region setting
is deliberately an explicit server startup choice; omitting or mistyping it disables
bounded construction and selection actions.

Set a real cuboid selection for the connected player without changing blocks:

```json
{"action":"select_cuboid","username":"SomePlayer","min":{"x":3000,"y":100,"z":240},"max":{"x":3004,"y":103,"z":240}}
```

The selection must fit the allowed region, have at most 512 blocks, and use loaded
chunks. The bridge updates WorldEdit's actual `LocalSession` and returns the same
structured selection as `get_selection`, with `status:"selected"` and
`completed:true`. It does not move the player or edit blocks.

Preview exact block writes:

```json
{"action":"preview_set_blocks","username":"SomePlayer","blocks":[{"x":3000,"y":101,"z":240,"block":"minecraft:stone_bricks"}]}
```

The response includes `preview_id`, a 60-second expiration, dimension, actual write
bounds, inspected and expected-changed block counts, target and overwritten palettes,
overwritten block entities, loaded-chunk status, and a fingerprint of the observed
pre-state. Preview is read-only and a successful preview reports `completed:false`.
The ID binds an immutable operation to the observed blocks. If any target changes or
unloads, the player changes dimension, or the preview expires, application is rejected.

Preview a uniform cuboid through the same exact-write machinery:

```json
{"action":"preview_fill_cuboid","username":"SomePlayer","min":{"x":3000,"y":100,"z":240},"max":{"x":3003,"y":103,"z":243},"block":"minecraft:stone_bricks"}
```

Its preview reports `operation_type:"fill_cuboid"` and inspects every cell in the
inclusive cuboid. It only accepts loaded air cells and the same inert palette. This
operation is for small workflow prototypes, not large builds or terrain replacement.

Apply the preview once:

```json
{"action":"apply_preview","username":"SomePlayer","preview_id":"<UUID from preview>"}
```

This uses a WorldEdit `EditSession` on Minecraft's server thread. `ok:true` and
`completed:true` mean the actual post-state of every target was checked against the
preview. The response includes actual changed-block count, affected bounds, WorldEdit
change-set size, post-state fingerprint, and an `operation_id`. A partial failure can
still return an `operation_id` and `undo_available:true`; it is never reported as a
completed build. The change-set size is diagnostic, not a claimed changed-block count.

Undo only that operation:

```json
{"action":"undo_operation","username":"SomePlayer","operation_id":"<UUID from apply>"}
```

Undo first checks that the loaded targets still have the operation's observed
post-state or the already-restored original state. It uses the retained WorldEdit
change set when possible, or an exact-state reversal through a WorldEdit edit session,
and verifies every original block state.
`ok:true` and `completed:true` mean restoration was observed. `more_bridge_undo_history`
describes only this bridge's retained operation; the player's other WorldEdit history
is unknown. The bridge refuses to overwrite later edits at target positions.

Previews expire in memory. Operation records are saved under the **active world
save's** `data/weditmcpbridge-operations.json`, with the exact planned target states
persisted before mutation and actual states recorded afterward. The journal retains
one undoable operation plus up to 16 recent summaries. On restart, the bridge can
recover targeted undo by using WorldEdit to restore the saved original states,
provided every affected chunk is loaded and each target still matches its recorded
post-state or already-restored original state. A crash during application may leave
a prepared record; the bridge then accepts only cells matching their exact before
or intended states, records whether application was complete or partial, and permits
bounded reversal. Conflicting or unloaded cells stop recovery. The active allowed
region and player/dimension checks still apply. A journal read/write error disables
new construction rather than silently dropping undo evidence. Undo after restart
reports `used_worldedit_history:false` because WorldEdit's in-memory change set does
not survive a restart. The journal is tied to the save's absolute path; moving or
copying a save with a pending bridge operation requires manual review rather than
automatically reusing its undo record in a different world directory.

Inspect one retained operation or list recent records:

```json
{"action":"get_operation_status","username":"SomePlayer","operation_id":"<UUID from apply>"}
{"action":"list_operations","username":"SomePlayer"}
```

The first returns `operation` with status, affected bounds, counts, timestamps,
fingerprints, and a live undo-availability check for the pending operation. The
second returns newest-first `operations` (up to 17 including pending). Neither
exposes the journal's block snapshots through the socket. These records describe
only operations initiated through this bounded bridge.

If a response times out after the server thread started an action, the bridge returns
`completion_unknown`; it does not claim that no edit occurred. Once the server is
responsive, query the read-only recovery action before retrying:

```json
{"action":"get_pending_operation","username":"SomePlayer"}
```

It returns the pending bridge `operation_id`, whether the original apply completed,
affected bounds, changed-block count, and whether the loaded world still matches the
observed post-state. An `operation_id` can then be used for targeted undo. A timeout
before an action starts cancels that queued action and returns `timeout`.

These safeguards cover the explicit writes made by this bridge. Minecraft side effects
and edits from other mods may alter adjacent blocks; use a disposable world and inspect
the area after every operation. Arbitrary CraftScripts are not bounded by this path.

The original command format is disabled by default because it can bypass the allowed
region. It can be re-enabled only by explicitly setting
`WEDIT_BRIDGE_ALLOW_LEGACY_COMMANDS=true` in the server process environment:

```json
{"username":"SomePlayer","command":"/cs myscript arg1"}
```

Its response includes `status:"dispatched"` and `completed:false`. `handled:true` only
means WorldEdit intercepted the command. It does **not** establish that a CraftScript
finished, blocks changed, or undo occurred. WorldEdit command feedback still goes to
the player chat. Do not use this legacy path as construction completion evidence.

## Building

1. Use Java 21, Minecraft 1.21.1, and NeoForge 21.1.233 or newer.
2. Obtain the WorldEdit 7.3.8 NeoForge/Fabric `worldedit-mod-7.3.8.jar` from the
   [official release](https://modrinth.com/plugin/worldedit/version/7.3.8).
3. Copy `local.properties.example` to ignored `local.properties`; set
   `worldedit.jarPath` to the absolute jar path. The jar is a compile-only dependency.
4. Run `./gradlew.bat build` on Windows, or `./gradlew build` on other systems.
5. Install this mod and WorldEdit into the same NeoForge development server/client.
   Confirm the loopback listener message in the log.

Neither this fork nor the Minecraft: Dune project bundles WorldEdit. Minecraft: Dune
builds and runs independently of this development bridge.
