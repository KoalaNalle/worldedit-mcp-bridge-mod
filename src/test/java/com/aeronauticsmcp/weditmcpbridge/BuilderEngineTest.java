package com.aeronauticsmcp.weditmcpbridge;

import static com.aeronauticsmcp.weditmcpbridge.BuilderEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BuilderEngineTest {
  @TempDir Path dir;
  static final String WORLD = "00000000-0000-4000-8000-000000000001",
      PROJECT = "00000000-0000-4000-8000-000000000002",
      PHASE = "00000000-0000-4000-8000-000000000003";
  static final String AIR = "minecraft:air", STONE = "minecraft:stone_bricks";
  final WorldIdentity identity = new WorldIdentity(WORLD, "0", "test", "original");
  final Bounds site = new Bounds(new Pos(0, 0, 0), new Pos(15, 15, 15));
  FakeWorld world;
  BuilderEngine engine;
  BuilderEngine.FileStore store;

  static class FakeWorld implements World {
    final Map<Pos, String> blocks = new HashMap<>();
    int writeCalls = 0, failAfter = -1;
    boolean checkpointFailure = false, unloaded = false;
    final Set<String> unavailableStates = new HashSet<>();

    public void validateState(String state) {
      require(!unavailableStates.contains(state), "unsupported_state", "Injected unavailable state");
      require(PALETTE.contains(state) || isAir(state), "block_not_allowed", "Unsupported state");
    }

    public String read(Pos p) {
      if (unloaded) throw new Failure("chunk_unloaded", "Not loaded");
      return blocks.getOrDefault(p, AIR);
    }

    public void write(List<Write> writes, boolean undo) {
      writeCalls++;
      int n = 0;
      for (Write w : writes) {
        if (n++ == failAfter) throw new IllegalStateException("injected interruption");
        blocks.put(w.pos(), undo ? w.before() : w.after());
      }
    }

    public void checkpoint() {
      if (checkpointFailure) throw new IllegalStateException("injected save failure");
    }
  }

  @BeforeEach
  void setup() {
    world = new FakeWorld();
    store = new BuilderEngine.FileStore(dir.resolve("journal.json"));
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
  }

  JsonObject call(String action, JsonObject args) {
    return engine.handle(action, args, "owner", "minecraft:overworld", world);
  }

  JsonObject args() {
    return obj("project_id", PROJECT);
  }

  JsonObject good(String action, JsonObject args) {
    JsonObject r = call(action, args);
    assertTrue(r.get("ok").getAsBoolean(), r.toString());
    return r;
  }

  void error(String code, String action, JsonObject args) {
    JsonObject r = call(action, args);
    assertFalse(r.get("ok").getAsBoolean(), r.toString());
    assertEquals(code, r.get("error_code").getAsString(), r.toString());
  }

  void create() {
    good("create_project", obj("project_id", PROJECT, "name", "gatehouse", "bounds", site));
  }

  void start(String id) {
    good("start_phase", obj("project_id", PROJECT, "phase_id", id, "name", "phase"));
  }

  JsonObject fill(String phase, int x, int length) {
    return obj(
        "schema",
        SCHEMA,
        "project_id",
        PROJECT,
        "phase_id",
        phase,
        "operations",
        List.of(
            obj(
                "type",
                "fill_cuboid",
                "min",
                new Pos(x, 0, 0),
                "max",
                new Pos(x + length - 1, 0, 0),
                "block",
                STONE)));
  }

  JsonObject preview(String phase, int x, int length) {
    return good("preview_build_plan", obj("project_id", PROJECT, "plan", fill(phase, x, length)));
  }

  JsonObject applyArgs(JsonObject preview) {
    return obj(
        "project_id",
        PROJECT,
        "preview_id",
        preview.get("preview_id"),
        "plan_hash",
        preview.get("plan_hash"));
  }

  void apply(String phase, int x, int length) {
    good("apply_build_plan", applyArgs(preview(phase, x, length)));
  }

  Project project() {
    return engine.root.projects.getFirst();
  }

  @Test
  void retainedPhasesReverseUndoReplacementAndWholeRollbackSurviveReload() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    apply(PHASE, 2, 2);
    good("complete_phase", obj("project_id", PROJECT, "phase_id", PHASE));
    String roof = UUID.randomUUID().toString();
    start(roof);
    apply(roof, 4, 2);
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    assertEquals(3, operations(project()).size());
    assertTrue(
        good("get_project", args()).getAsJsonObject("verification").get("matches").getAsBoolean());
    error("out_of_order", "undo_latest_phase", obj("project_id", PROJECT, "phase_id", PHASE));
    good("undo_latest_phase", obj("project_id", PROJECT, "phase_id", roof));
    assertEquals(STONE, world.read(new Pos(0, 0, 0)));
    assertEquals(AIR, world.read(new Pos(4, 0, 0)));
    String replacement = UUID.randomUUID().toString();
    start(replacement);
    apply(replacement, 4, 3);
    List<String> ids = operations(project()).stream().map(o -> o.operation_id).toList();
    good("rollback_project", args());
    assertEquals(List.of(ids.get(2), ids.get(3), ids.get(1), ids.get(0)), project().undo_order);
    assertEquals(ProjectState.ROLLED_BACK, project().state);
    assertTrue(world.blocks.values().stream().allMatch(AIR::equals));
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    assertTrue(
        good("get_project", args()).getAsJsonObject("verification").get("matches").getAsBoolean());
    assertEquals(4, operations(project()).size());
  }

  @Test
  void externalConflictPreflightsEntireRollbackAndPreservesBlocks() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    apply(PHASE, 2, 2);
    world.blocks.put(new Pos(0, 0, 0), "minecraft:gold_block");
    int calls = world.writeCalls;
    error("rollback_conflict", "rollback_project", args());
    assertEquals(calls, world.writeCalls);
    assertEquals(ProjectState.CONFLICTED, project().state);
    assertEquals(STONE, world.read(new Pos(3, 0, 0)));
    world.blocks.put(new Pos(0, 0, 0), STONE);
    good("rollback_project", args());
    assertEquals(AIR, world.read(new Pos(0, 0, 0)));
  }

  @Test
  void manualAirIsAlsoConflictNotAssumedPartial() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    world.blocks.put(new Pos(0, 0, 0), AIR);
    error("rollback_conflict", "rollback_project", args());
    error(
        "rollback_conflict",
        "recover_project",
        obj("project_id", PROJECT, "mode", "rollback_incomplete"));
  }

  @Test
  void changedRestoredPositionIsNotSilentlyReportedRolledBack() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    good("rollback_project", args());
    world.blocks.put(new Pos(0, 0, 0), STONE);
    error("rollback_conflict", "rollback_project", args());
  }

  @Test
  void partialApplyRequiresExplicitRecovery() {
    create();
    start(PHASE);
    world.failAfter = 1;
    error("completion_unknown", "apply_build_plan", applyArgs(preview(PHASE, 0, 3)));
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    good("get_project", args());
    assertEquals(State.UNKNOWN, operations(project()).getFirst().state);
    error("recovery_required", "rollback_project", args());
    world.failAfter = -1;
    good("recover_project", obj("project_id", PROJECT, "mode", "rollback_incomplete"));
    assertTrue(world.blocks.values().stream().allMatch(AIR::equals));
  }

  @Test
  void partialUndoRequiresExplicitRecovery() {
    create();
    start(PHASE);
    apply(PHASE, 0, 3);
    world.failAfter = 1;
    error("completion_unknown", "rollback_project", args());
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    world.failAfter = -1;
    good("recover_project", obj("project_id", PROJECT, "mode", "rollback_incomplete"));
    assertEquals(ProjectState.ROLLED_BACK, project().state);
  }

  @Test
  void recoveryNeverOverwritesThirdPartyState() {
    create();
    start(PHASE);
    world.failAfter = 1;
    error("completion_unknown", "apply_build_plan", applyArgs(preview(PHASE, 0, 3)));
    world.failAfter = -1;
    world.blocks.put(new Pos(1, 0, 0), "minecraft:gold_block");
    error(
        "rollback_conflict",
        "recover_project",
        obj("project_id", PROJECT, "mode", "rollback_incomplete"));
    assertEquals("minecraft:gold_block", world.read(new Pos(1, 0, 0)));
  }

  @ParameterizedTest
  @EnumSource(
      value = State.class,
      names = {"PREPARED", "APPLYING", "UNDOING", "APPLIED"})
  void deterministicRecoveryMatrix(State state) {
    if (state == State.APPLIED) {
      assertEquals(State.APPLIED, recoveryDecision(state, 0, 3, 0, 3));
      return;
    }
    assertEquals(State.UNKNOWN, recoveryDecision(state, 1, 1, 1, 3));
    assertEquals(State.UNKNOWN, recoveryDecision(state, 1, 2, 0, 3));
    assertEquals(
        state == State.UNDOING ? State.UNDONE : State.FAILED, recoveryDecision(state, 3, 0, 0, 3));
    assertEquals(
        state == State.UNDOING ? State.UNKNOWN : State.APPLIED,
        recoveryDecision(state, 0, 3, 0, 3));
  }

  @Test
  void appliedJournalWithUnappliedWorldIsConflict() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    world.blocks.clear();
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    assertFalse(
        good("get_project", args()).getAsJsonObject("verification").get("matches").getAsBoolean());
  }

  @Test
  void phaseCompletionRetainsSnapshots() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    good("complete_phase", obj("project_id", PROJECT, "phase_id", PHASE));
    assertEquals(2, operations(store.load().projects.getFirst()).getFirst().writes.size());
  }

  @Test
  void phaseLifecycleAndOwnership() {
    create();
    error(
        "duplicate_project",
        "create_project",
        obj("project_id", PROJECT, "name", "gatehouse", "bounds", site));
    error(
        "no_open_phase",
        "preview_build_plan",
        obj("project_id", PROJECT, "plan", fill(PHASE, 0, 1)));
    start(PHASE);
    error(
        "phase_open",
        "start_phase",
        obj("project_id", PROJECT, "phase_id", UUID.randomUUID().toString(), "name", "second"));
    error("phase_incomplete", "complete_phase", obj("project_id", PROJECT, "phase_id", PHASE));
    assertEquals(
        "owner_mismatch",
        engine
            .handle("get_project", args(), "other", "minecraft:overworld", world)
            .get("error_code")
            .getAsString());
  }

  @Test
  void planHashAndStaleBlockAndPhaseRevision() {
    create();
    start(PHASE);
    JsonObject preview = preview(PHASE, 0, 1);
    JsonObject args = applyArgs(preview);
    args.addProperty("plan_hash", "0".repeat(64));
    error("plan_hash_mismatch", "apply_build_plan", args);
    world.blocks.put(new Pos(0, 0, 0), STONE);
    error("stale_preview", "apply_build_plan", applyArgs(preview));
    world.blocks.clear();
    apply(PHASE, 1, 1);
    error("stale_preview", "apply_build_plan", applyArgs(preview));
  }

  @Test
  void invalidIdsAndMalformedPlans() {
    create();
    start(PHASE);
    error("invalid_id", "get_project", obj("project_id", "../escape"));
    JsonObject p = fill(PHASE, 0, 1);
    p.addProperty("code", "danger");
    error("invalid_request", "preview_build_plan", obj("project_id", PROJECT, "plan", p));
  }

  @Test
  void canonicalHashIgnoresInputKeyAndPlacementOrder() {
    String p = UUID.randomUUID().toString();
    JsonObject a =
        obj(
            "schema",
            SCHEMA,
            "project_id",
            PROJECT,
            "phase_id",
            p,
            "operations",
            List.of(
                obj(
                    "type",
                    "place_blocks",
                    "placements",
                    List.of(
                        obj("pos", new Pos(1, 0, 0), "block", STONE),
                        obj("pos", new Pos(0, 0, 0), "block", STONE)))));
    JsonObject b = JsonParser.parseString(a.toString()).getAsJsonObject();
    JsonArray list =
        b.getAsJsonArray("operations").get(0).getAsJsonObject().getAsJsonArray("placements");
    JsonElement first = list.remove(0);
    list.add(first);
    assertEquals(hash(parsePlan(a)), hash(parsePlan(b)));
  }

  @Test
  void limitsMatchPublishedContract() throws Exception {
    JsonObject fixture =
        JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/builder-capabilities.json")))
            .getAsJsonObject();
    assertEquals(capabilities(), fixture);
  }

  @Test
  void fillAndExplicitBoundariesAndUnsupportedBlocks() {
    JsonObject p = fill(PHASE, 0, 512);
    assertEquals(512, parsePlan(p).operations().getFirst().targets().size());
    assertThrows(Failure.class, () -> parsePlan(fill(PHASE, 0, 513)));
    for (int length : List.of(64, 65)) {
      JsonObject plan =
          obj(
              "schema",
              SCHEMA,
              "project_id",
              PROJECT,
              "phase_id",
              PHASE,
              "operations",
              List.of(
                  obj(
                      "type",
                      "place_blocks",
                      "placements",
                      List.of(
                          obj("pos", new Pos(0, 0, 0), "block", STONE),
                          obj("pos", new Pos(length - 1, 0, 0), "block", STONE)))));
      if (length == 64) assertNotNull(parsePlan(plan));
      else assertThrows(Failure.class, () -> parsePlan(plan));
    }
    p.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("block", "minecraft:chest");
    assertThrows(Failure.class, () -> parsePlan(p));
  }

  @Test
  void expandedPaletteRetainsExactStatesAndRestoresAfterReload() {
    create();
    start(PHASE);
    List<String> palette = new TreeSet<>(PALETTE).stream().toList();
    List<JsonObject> placements = new ArrayList<>();
    for (int i = 0; i < palette.size(); i++) {
      world.blocks.put(new Pos(i, 0, 0), i % 2 == 0 ? "minecraft:cave_air" : AIR);
      placements.add(obj("pos", new Pos(i, 0, 0), "block", palette.get(i)));
    }
    JsonObject plan = obj("schema", SCHEMA, "project_id", PROJECT, "phase_id", PHASE,
        "operations", List.of(obj("type", "place_blocks", "placements", placements)));
    JsonObject preview = good("preview_build_plan", obj("project_id", PROJECT, "plan", plan));
    good("apply_build_plan", applyArgs(preview));
    good("complete_phase", obj("project_id", PROJECT, "phase_id", PHASE));
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    assertTrue(good("get_project", args()).getAsJsonObject("verification").get("matches").getAsBoolean());
    for (int i = 0; i < palette.size(); i++) assertEquals(palette.get(i), world.read(new Pos(i, 0, 0)));
    good("rollback_project", args());
    for (int i = 0; i < palette.size(); i++)
      assertEquals(i % 2 == 0 ? "minecraft:cave_air" : AIR, world.read(new Pos(i, 0, 0)));
    assertEquals(palette.size(), operations(store.load().projects.getFirst()).getFirst().writes.size());
  }

  @Test
  void unavailableRuntimeStateFailsBeforePreviewAndBeforeAnyApplyWrites() {
    create();
    start(PHASE);
    world.unavailableStates.add(STONE);
    error("unsupported_state", "preview_build_plan", obj("project_id", PROJECT, "plan", fill(PHASE, 0, 1)));
    assertEquals(0, world.writeCalls);
    assertEquals(0, operations(project()).size());
    world.unavailableStates.clear();
    JsonObject preview = preview(PHASE, 0, 1);
    world.unavailableStates.add(STONE);
    error("unsupported_state", "apply_build_plan", applyArgs(preview));
    assertEquals(0, world.writeCalls);
    assertEquals(0, operations(project()).size());
    world.unavailableStates.clear();
    good("apply_build_plan", applyArgs(preview));
    world.unavailableStates.add(AIR);
    error("unsupported_state", "rollback_project", args());
    assertEquals(1, world.writeCalls);
    assertEquals(STONE, world.read(new Pos(0, 0, 0)));
  }

  @Test
  void arbitraryOrStatefulBlocksRemainRejected() {
    for (String id : List.of("minecraft:gold_block", "minecraft:chest", "create:belt",
        "minecraft:water", "minecraft:sand", "minecraft:sandstone_stairs[facing=north]",
        "create:polished_cut_limestone[axis=y]", "unknown:missing", "minecraft:air")) {
      JsonObject plan = fill(PHASE, 0, 1);
      plan.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("block", id);
      assertEquals("block_not_allowed", assertThrows(Failure.class, () -> parsePlan(plan)).code);
    }
  }

  @Test
  void siteBoundsAndUnloadedWorld() {
    create();
    start(PHASE);
    error(
        "outside_project_bounds",
        "preview_build_plan",
        obj("project_id", PROJECT, "plan", fill(PHASE, 16, 1)));
    world.unloaded = true;
    error(
        "chunk_unloaded",
        "preview_build_plan",
        obj("project_id", PROJECT, "plan", fill(PHASE, 0, 1)));
  }

  @Test
  void movedCopyRequiresExactExplicitAcknowledgement() {
    create();
    engine = new BuilderEngine(store, identity, "copied", "minecraft:overworld", site);
    error("moved_or_copied_world", "get_project", args());
    good(
        "acknowledge_world_move",
        obj("world_id", WORLD, "previous_path", "original", "current_path", "copied"));
    good("get_project", args());
    assertTrue(Files.exists(store.path));
    assertThrows(
        Failure.class,
        () ->
            new BuilderEngine(
                store,
                new WorldIdentity(UUID.randomUUID().toString(), "0", "other", "copied"),
                "copied",
                "minecraft:overworld",
                site));
  }

  @Test
  void corruptedJournalRejectedAndTemporaryFileIgnored() throws Exception {
    create();
    Files.writeString(store.path.resolveSibling("journal.json.tmp"), "incomplete");
    assertNotNull(store.load());
    Files.writeString(store.path, "{}");
    assertThrows(Failure.class, store::load);
  }

  @Test
  void persistenceFailureBeforeMutationStopsWrites() {
    create();
    start(PHASE);
    Store failing =
        new Store() {
          public Root load() {
            return store.load();
          }

          public void save(Root r) {
            throw new IllegalStateException("disk full");
          }
        };
    engine = new BuilderEngine(failing, identity, "original", "minecraft:overworld", site);
    error("journal_unavailable", "apply_build_plan", applyArgs(preview(PHASE, 0, 1)));
    assertEquals(0, world.writeCalls);
    error("journal_unavailable", "get_project", args());
  }

  @Test
  void storageHistoryLimitNeverEvictsUndoneSnapshots() {
    create();
    for (int i = 0; i < HISTORY; i++) {
      String phase = UUID.randomUUID().toString();
      start(phase);
      apply(phase, 0, 1);
      good("undo_latest_phase", obj("project_id", PROJECT, "phase_id", phase));
    }
    assertEquals(HISTORY, operations(store.load().projects.getFirst()).size());
    error(
        "history_limit",
        "start_phase",
        obj("project_id", PROJECT, "phase_id", UUID.randomUUID().toString(), "name", "too-many"));
  }

  @Test
  void saveFailureAfterWorldWriteReconcilesActualWorldWithoutDuplicateApply() {
    create();
    start(PHASE);
    world.checkpointFailure = true;
    error("completion_unknown", "apply_build_plan", applyArgs(preview(PHASE, 0, 2)));
    world.checkpointFailure = false;
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    assertTrue(
        good("get_project", args()).getAsJsonObject("verification").get("matches").getAsBoolean());
    assertEquals(State.APPLIED, operations(project()).getFirst().state);
    assertEquals(1, world.writeCalls);
    good("rollback_project", args());
    assertEquals(AIR, world.read(new Pos(0, 0, 0)));
  }

  @Test
  void lostFinalJournalWriteLeavesRecoverableApplyingRecord() {
    create();
    start(PHASE);
    Store fault =
        new Store() {
          int saves = 0;

          public Root load() {
            return store.load();
          }

          public void save(Root r) {
            if (++saves == 3) throw new IllegalStateException("injected final journal failure");
            store.save(r);
          }
        };
    engine = new BuilderEngine(fault, identity, "original", "minecraft:overworld", site);
    error("completion_unknown", "apply_build_plan", applyArgs(preview(PHASE, 0, 2)));
    assertEquals(State.APPLYING, operations(store.load().projects.getFirst()).getFirst().state);
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    assertTrue(
        good("get_project", args()).getAsJsonObject("verification").get("matches").getAsBoolean());
    good("rollback_project", args());
    assertTrue(world.blocks.values().stream().allMatch(AIR::equals));
  }

  @Test
  void preparedBeforeMutationAndAggregateLimits() {
    create();
    start(PHASE);
    apply(PHASE, 0, 2);
    Operation op = operations(project()).getFirst();
    op.state = State.PREPARED;
    world.blocks.clear();
    store.save(engine.root);
    engine = new BuilderEngine(store, identity, "original", "minecraft:overworld", site);
    good("get_project", args());
    assertEquals(State.FAILED, operations(project()).getFirst().state);
    JsonObject oversized = fill(PHASE, 0, 65);
    JsonArray nine = new JsonArray();
    for (int i = 0; i < 9; i++) nine.add(oversized.getAsJsonArray("operations").get(0));
    oversized.add("operations", nine);
    assertThrows(Failure.class, () -> parsePlan(oversized));
    nine.remove(0);
    assertThrows(Failure.class, () -> parsePlan(oversized));
    JsonObject unsupported = fill(PHASE, 0, 1);
    unsupported
        .getAsJsonArray("operations")
        .get(0)
        .getAsJsonObject()
        .addProperty("type", "run_command");
    assertThrows(Failure.class, () -> parsePlan(unsupported));
  }
}
