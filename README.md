# weditmcpbridge

A minimal NeoForge companion mod for [mcp-worldedit-craftscript](../mcp-worldedit-craftscript).

## Why this exists

The MCP server was originally designed to drive WorldEdit entirely over RCON, using
`execute as <player> at <player> run ...` to make commands act as a specific player. That
does **not** work: WorldEdit resolves its "actor" (whose selection/undo history to use) from
the real client connection, not from Brigadier's substituted command source — confirmed
empirically (vanilla `execute as` correctly attributes chat messages to the player, but
WorldEdit-specific commands silently no-op regardless).

It turns out WorldEdit's `//`-prefixed commands (`//size`, `//cs`, `//undo`, etc.) aren't even
registered in vanilla's Brigadier command tree at all — they're intercepted via WorldEdit's own
chat-handling hook. So there's no command string that RCON, or even a genuine player-derived
`CommandSourceStack` dispatched through vanilla Brigadier, can send to reach them.

This mod runs inside the same JVM as WorldEdit and dispatches commands through WorldEdit's
**own** command pipeline instead: it adapts a real `ServerPlayer` into a WorldEdit `Actor`
(`NeoForgeAdapter.adaptPlayer`) and posts a `CommandEvent` to WorldEdit's own event bus — the
same path a genuinely typed command takes. WorldEdit then correctly recognizes it as coming
from that player.

## What it does

Exposes a **localhost-only** TCP endpoint (default `127.0.0.1:25577`) speaking a trivial
one-line-JSON-in, one-line-JSON-out protocol:

```
→ {"username": "SomePlayer", "command": "/cs myscript arg1 arg2"}
← {"ok": true, "handled": true}
```

- `command` should be exactly what a player would type, including the `/` or `//` prefix.
  Region-editing commands use `//` (`//size`, `//undo N`); craftscripts use a single `/`
  (`/cs <script> <args...>`) — these are genuinely different prefixes in WorldEdit, not a typo.
- `handled: true` means WorldEdit's command manager processed it. `ok: false` with an `error`
  means an exception was thrown (e.g. the player wasn't found).
- WorldEdit's own feedback (selection details, error messages) goes to the player's real chat,
  same as if they'd typed the command — this bridge doesn't currently capture that text.

Never bind this to anything but `127.0.0.1`. It has no authentication of its own — it trusts
whatever connects to it, which is only meant to be the MCP server on the same machine.

## Building

1. Copy `local.properties.example` to `local.properties` and set `worldedit.jarPath` to your
   server's installed `worldedit-mod-*.jar` (compiled against directly, for exact API match —
   not fetched from Maven).
2. `./gradlew.bat build` (Windows) or `./gradlew build` (Linux/Mac).
3. Copy `build/libs/weditmcpbridge-*.jar` into your server's `mods/` folder.
4. Restart the server. Look for `weditmcpbridge listening on 127.0.0.1:25577` in the log.

## Configuration

- `WEDIT_BRIDGE_PORT` (environment variable on the Minecraft server process, optional) — port
  to listen on, default `25577`.

## License

MIT
