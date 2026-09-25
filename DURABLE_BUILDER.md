# Durable builder protocol 2

The bridge is authoritative. The matching local MCP server is
[KoalaNalle/mcp-worldedit-craftscript](https://github.com/KoalaNalle/mcp-worldedit-craftscript);
the companion is [KoalaNalle/worldedit-mcp-bridge-mod](https://github.com/KoalaNalle/worldedit-mcp-bridge-mod).
Both preserve Lucas-Buckley's original attribution and licenses. Observation remains in
KoalaNalle/minecraft-mcp. Minecraft-Dune has no dependency on these optional components.

## Workflow

Use local stdio with `WORLDEDIT_MCP_PROFILE=builder`; the default observation profile still
exposes only selection inspection. Start Minecraft with an explicit loopback bridge region:

```text
WEDIT_BRIDGE_ALLOWED_REGION=minecraft:overworld;-14,65,6;-5,72,14
```

1. Observe the site with the observation MCP; read `builder_capabilities`.
2. `create_project`: supply UUID, name, inclusive bounds, online operator username.
3. `start_phase`: supply project and phase UUIDs, name and username.
4. `preview_build_plan`: supply the exact expanded declarative plan below.
5. Review returned bounds, actual before palette, counts, digest, and plan hash.
6. `apply_build_plan`: supply project UUID, preview UUID and the exact plan hash.
7. Inspect actual blocks and images. `complete_phase` retains all snapshots.
8. Start the next phase. `undo_latest_phase` requires the newest applicable phase ID.
9. `rollback_project` reverses remaining phases and operations in strict LIFO order.

```json
{
  "schema": "dune-build-plan/v1",
  "project_id": "92102609-0000-4000-8000-000000000001",
  "phase_id": "92102609-0000-4000-8000-000000000011",
  "operations": [
    {
      "type": "fill_cuboid",
      "min": {"x": -12, "y": 65, "z": 8},
      "max": {"x": -7, "y": 65, "z": 12},
      "block": "minecraft:cut_sandstone"
    },
    {
      "type": "place_blocks",
      "placements": [
        {"pos": {"x": -12, "y": 66, "z": 8}, "block": "minecraft:sandstone"}
      ]
    }
  ]
}
```

Unknown fields, expressions, scripts, commands, unsupported states and duplicate explicit
positions are rejected. Canonical form expands fills, sorts positions within each primitive,
retains primitive order and serializes fixed fields as UTF-8 JSON before SHA-256. Preview
stores that exact immutable form; apply does not regenerate a plan. Tokens expire after
60 seconds, with at most 8 outstanding. Any project revision, phase or relevant block change
invalidates application. Previews do not change world blocks but allocate transient state.

## Limits and block states

| Scope | Limit |
| --- | ---: |
| Project bounds | 4,096 cells |
| Fill operation bounds and writes | 512 cells |
| Explicit operation bounds and writes | 64 cells |
| Operations per plan | 8 |
| Total writes per plan | 512 |
| Retained operations per project (including undone) | 32 |
| Retained phases per project | 32 |
| Retained projects per save | 4 |
| Preview tokens | 8 |
| Returned conflict positions | 32, with full conflict count |
| Request / response | 64 KiB each |
| Journal | 16 MiB |

Per-operation limits were not increased. Explicit bounds are now consistently 64 cells
in Java and TypeScript. The larger project site groups many small operations. At the worst
case there are 65,536 retained snapshots across a save, with two short full-state strings
and integer coordinates per snapshot. The 16 MiB serialized ceiling is enforced before
replacement; capacity exhaustion fails closed and never evicts a snapshot. No automatic
history compaction is implemented. A new disposable save is the supported way to start a
fresh test after exhausting this conservative history budget.

`builder_capabilities` is the authoritative machine contract; TypeScript reads it before
plan/project validation. Both test suites assert the checked-in contract fixtures.
Targets retain the five original stone-brick/sandstone IDs and add only these inspected
royal-palace candidates: `create:polished_cut_limestone`, `create:cut_limestone`,
`create:cut_limestone_bricks`, `create:polished_cut_calcite`,
`minecraft:chiseled_quartz_block`, `create:industrial_iron_block`,
`create:brass_block`, and `supplementaries:deepslate_lamp`. This is a bounded
allowlist, not permission for arbitrary registry blocks. A listed candidate may still
be unavailable or unsafe in the current modpack: the loaded registry must contain it,
its exact WorldEdit default state must equal its bare ID, it must have no properties,
full collision, no block entity or fluid, no falling behavior, no random ticks and no
signal-source behavior. The explicit ID selection remains the review boundary for
interaction behavior; these mechanical checks do not certify arbitrary mod blocks.
The bridge reports optional `palette_metadata` (ID, display name, source namespace,
class, full default state, light emission and safety rejection reasons) through
capabilities. A listed block with `safe:false` cannot be built. Missing optional mods
do not disable the original palette. Legacy protocol-1 palette support is unchanged.
Preview validates targets before retaining a token. Apply and reversal preflight all
states before journal transitions, and the adapter resolves the complete operation
before opening its WorldEdit edit session. Writes require air or the identical desired state.
Snapshots retain the full canonical WorldEdit block-state string, and restoration rejects
any string that cannot round-trip exactly. Existing stateful terrain, block entities,
fluids, gravity blocks, redstone, entities and Create machines cannot be overwritten.
Stairs/slabs/walls and richer states remain unsupported.

## Authority and lifecycle

Projects bind persistent world UUID, seed, dimension, owner player UUID, bounds, limits,
creation time, phase history and operation history. Only one phase can be OPEN.
Completing a phase retains all before/after snapshots. Active project sites cannot overlap.
The project lifecycle is ACTIVE / ROLLING_BACK / ROLLED_BACK / CONFLICTED. Phase lifecycle
is OPEN / COMPLETE / UNDONE (CONFLICTED reserved). No finalization or destructive compaction.

Each operation has exactly one state: PREPARED, APPLYING, APPLIED, UNDOING, UNDONE,
FAILED, UNKNOWN (CONFLICTED reserved). PREPARED snapshots are persisted before writes;
APPLYING is persisted immediately before the WorldEdit EditSession. Actual blocks are
compared with intended states, Minecraft is saved, and only then is APPLIED persisted.
Undo follows UNDOING → exact before-state verification → Minecraft save → UNDONE.
`world_verified:true` and `journal_persisted:true` are required for MCP mutation success.

All game access occurs on the Minecraft server thread. No network waits occur there.
The socket has 2–4 workers, a 32-request queue, 64 KiB requests and a 10-second action
response deadline. An action cancelled before start cannot later execute. If it already
started when the deadline expires, the response is `completion_unknown`: inspect
`get_project` before retrying. World saves can make completion exceed the response deadline;
they are deliberate development-tool overhead, never terrain benchmark measurements.

## Conflict-safe reversal

Rollback first simulates the complete reverse sequence against loaded actual blocks.
Any unexpected state returns `rollback_conflict`, a bounded list of positions and expected/
current states, and zero writes. Earlier phases remain intact when undoing the latest.
Even a manually removed block (now air) is a conflict for a normally APPLIED operation.
No automatic overwrite or force mode is exposed. Restore the unexpected edit through an
explicitly authorized external action, inspect the project, then retry rollback.

After a lost response/restart, `get_project` reconciles the full retained history against
the loaded world. `list_projects` is only an index and reports `world_verified:false`.
Unloaded chunks fail clearly; the builder never generates or loads them implicitly.
PREPARED/APPLYING can reconcile to FAILED (entirely original), APPLIED (entirely intended),
or UNKNOWN (partial/inconsistent). UNDOING becomes UNDONE only when all originals match.
APPLIED with conflicting world data remains a project conflict, including save/journal skew.

`recover_project` with explicit `mode:"rollback_incomplete"` acknowledges reversal of an
interrupted project. Only cells matching stored before/after states of an actually
interrupted operation are eligible. Third-party states remain conflicts. Recovery never
silently resumes an interrupted forward plan. Already applied operations still use strict
post-state checks. This path and all recovery observations are tested with injected faults.

## Persistence and save identity

Files under the active save's `data/`:

- `weditmcpbridge-world.json`: generated save UUID, seed, name and original diagnostic path.
- `weditmcpbridge-projects.json`: versioned journal envelope, SHA-256 checksum and full snapshots.

Writes use a same-directory temporary file, complete channel writes, `force(true)`, close,
and atomic replace. File systems without atomic replacement fail closed. A stray incomplete
temporary file never replaces the last valid record. Corrupt/missing identity or journal
data disables the builder; it is never deleted automatically. Checksums detect corruption,
not malicious edits by the local machine owner. Minecraft saves and journal writes are
separate stores, so recovery always consults actual blocks after interruption.

A matching UUID at a different path reports `moved_or_copied_world`. Administrative
`acknowledge_world_move` requires the UUID, exact previous binding and exact new path.
It retains that UUID/history and updates the journal binding; original metadata stays
as provenance. This is an explicit acknowledgement of a moved or copied development
save, not automatic cloning into a new identity. Never operate the source and copied
identity concurrently as a single project. Forking construction history into a new UUID
is deferred. No journal deletion or implicit migration occurs.

## Previous protocol and scripts

Protocol-1 anonymous preview/apply actions now return `project_required`. Their MCP names
are retained temporarily for clear upgrade errors and baseline client compatibility.
Existing protocol-1 read/targeted undo remains available solely to recover an old pending
operation. New project creation is blocked while that old journal has a pending operation.
The earlier journal is preserved. New construction uses BuildPlan and durable project IDs.
Legacy arbitrary CraftScripts remain outside builder profile and disabled by default at
the bridge. No script-text filtering is used as a sandbox.

## Tests and CI

Bridge: Java 21, `gradlew build`; pure JUnit tests exercise a deterministic fake block store,
real journal files, injected partial apply/undo/save failures, conflict checks, limits,
ownership, ordering, canonicalization and moved-save identity. These are not live Minecraft.
MCP: `npm ci`, `npm run typecheck`, `npm run test:protocol`; actual MCP SDK stdio clients
with explicitly mocked bridge replies exercise discovery, schemas and evidence transport.
Live world/Codex evidence is recorded separately in Minecraft-Dune's milestone report.

CI resolves the exact official [WorldEdit 7.3.8 release](https://modrinth.com/plugin/worldedit/version/7.3.8)
from Modrinth Maven as `maven.modrinth:worldedit:WTAFvuRx`. The build checks SHA-256
`5e7752c97876d87411e3760bcc573cc431f43c453722e6959fa7fe54db1b01ca`.
An ignored `local.properties` with `worldedit.jarPath` can select the same verified local
artifact. `gradlew build -Pworldedit.maven=true` exercises the CI dependency route even
when that local file exists. WorldEdit is compile-only; no WorldEdit or proprietary
Minecraft artifact is published in this repository. NeoForge/Gradle resolves its ordinary
Minecraft development dependencies in CI.
