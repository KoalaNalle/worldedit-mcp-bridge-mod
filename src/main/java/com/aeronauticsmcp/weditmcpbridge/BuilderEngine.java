package com.aeronauticsmcp.weditmcpbridge;

import com.google.gson.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Pure project/journal engine. Its caller serializes all access on the game thread. */
final class BuilderEngine {
  static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
  static final String SCHEMA = "dune-build-plan/v1", PROTOCOL = "dune-builder/2";
  static final int PROJECT_VOLUME = 4096,
      OP_VOLUME = 512,
      WRITES = 512,
      EXPLICIT = 64,
      PLAN_OPS = 8,
      HISTORY = 32,
      PROJECTS = 4,
      JOURNAL_BYTES = 16 * 1024 * 1024;
  static final Set<String> PALETTE =
      Set.of(
          "minecraft:stone_bricks",
          "minecraft:sandstone",
          "minecraft:smooth_sandstone",
          "minecraft:cut_sandstone",
          "minecraft:chiseled_sandstone",
          "create:polished_cut_limestone",
          "create:cut_limestone",
          "create:cut_limestone_bricks",
          "create:polished_cut_calcite",
          "minecraft:chiseled_quartz_block",
          "create:industrial_iron_block",
          "create:brass_block",
          "supplementaries:deepslate_lamp");

  enum State {
    PREPARED,
    APPLYING,
    APPLIED,
    UNDOING,
    UNDONE,
    CONFLICTED,
    FAILED,
    UNKNOWN
  }

  enum ProjectState {
    ACTIVE,
    ROLLING_BACK,
    ROLLED_BACK,
    CONFLICTED
  }

  enum PhaseState {
    OPEN,
    COMPLETE,
    UNDONE,
    CONFLICTED
  }

  record Pos(int x, int y, int z) implements Comparable<Pos> {
    public int compareTo(Pos p) {
      int c = Integer.compare(x, p.x);
      if (c == 0) c = Integer.compare(y, p.y);
      return c == 0 ? Integer.compare(z, p.z) : c;
    }
  }

  record Bounds(Pos min, Pos max) {
    long volume() {
      long x = (long) max.x - min.x + 1, y = (long) max.y - min.y + 1, z = (long) max.z - min.z + 1;
      require(x > 0 && y > 0 && z > 0, "invalid_bounds", "Corners must be ordered");
      return x > PROJECT_VOLUME || y > PROJECT_VOLUME || z > PROJECT_VOLUME
          ? Long.MAX_VALUE
          : x * y * z;
    }

    boolean contains(Pos p) {
      return p.x >= min.x
          && p.x <= max.x
          && p.y >= min.y
          && p.y <= max.y
          && p.z >= min.z
          && p.z <= max.z;
    }

    boolean contains(Bounds b) {
      return contains(b.min) && contains(b.max);
    }
  }

  record Write(Pos pos, String before, String after) {}

  record Target(Pos pos, String block) {}

  record Primitive(String type, List<Target> targets) {}

  record Plan(String schema, String project_id, String phase_id, List<Primitive> operations) {}

  record WorldIdentity(String world_id, String seed, String save_name, String original_path) {}

  interface World {
    // Must reject unloaded positions and block entities; never generate chunks.
    String read(Pos p);

    // Fail closed for missing or unsafe default states before preview, apply and reversal.
    void validateState(String state);

    void write(List<Write> writes, boolean undo) throws Exception;

    // Flush Minecraft chunks before publishing a durably completed operation.
    void checkpoint() throws Exception;
  }

  interface Store {
    Root load();

    void save(Root root);
  }

  static final class Failure extends RuntimeException {
    final String code;
    final JsonObject details;

    Failure(String code, String message) {
      this(code, message, new JsonObject());
    }

    Failure(String code, String message, JsonObject details) {
      super(message);
      this.code = code;
      this.details = details;
    }
  }

  static final class Operation {
    String operation_id,
        project_id,
        phase_id,
        world_id,
        dimension,
        plan_hash,
        pre_state_digest,
        post_state_digest,
        created_at;
    State state = State.PREPARED, recovery_from;
    String recovery_observation;
    List<Write> writes;
    Bounds bounds;
    int changed_blocks;
  }

  static final class Phase {
    String phase_id, name;
    PhaseState state = PhaseState.OPEN;
    List<Operation> operations = new ArrayList<>();
  }

  static final class Project {
    String project_id, name, owner, dimension, created_at;
    WorldIdentity world;
    Bounds bounds;
    JsonObject limits;
    ProjectState state = ProjectState.ACTIVE;
    long revision = 0;
    List<Phase> phases = new ArrayList<>();
    List<String> undo_order = new ArrayList<>();
  }

  static final class Root {
    String protocol_version = PROTOCOL;
    WorldIdentity world;
    List<Project> projects = new ArrayList<>();
  }

  record Preview(
      String id,
      String owner,
      String dimension,
      Plan plan,
      String hash,
      String digest,
      long revision,
      long expires,
      List<List<Write>> writes,
      Map<Pos, String> pre) {}

  private final Store store;
  final Root root;
  private final String currentPath;
  private final Bounds allowed;
  private final String dimension;
  private final Map<String, Preview> previews = new HashMap<>();
  private boolean healthy = true;

  BuilderEngine(
      Store store, WorldIdentity identity, String path, String dimension, Bounds allowed) {
    this.store = store;
    this.currentPath = path;
    this.allowed = allowed;
    this.dimension = dimension;
    Root loaded = store.load();
    root = loaded == null ? new Root() : loaded;
    if (loaded == null) {
      root.world = identity;
      persist();
    }
    require(
        root.world.world_id.equals(identity.world_id),
        "world_mismatch",
        "Journal and save UUID differ");
    require(
        root.world.seed.equals(identity.seed), "world_mismatch", "Save seed differs from journal");
    validateRoot(root);
  }

  static JsonObject capabilities() {
    JsonObject c =
        obj(
            "protocol_version",
            PROTOCOL,
            "supported_plan_schema",
            SCHEMA,
            "max_project_bounds_volume",
            PROJECT_VOLUME,
            "max_operation_bounds_volume",
            OP_VOLUME,
            "max_write_count",
            WRITES,
            "max_explicit_write_count",
            EXPLICIT,
            "max_explicit_bounds_volume",
            EXPLICIT,
            "max_operations_per_plan",
            PLAN_OPS,
            "max_plan_write_count",
            WRITES,
            "max_retained_project_operations",
            HISTORY,
            "max_projects_per_save",
            PROJECTS,
            "max_request_bytes",
            65536,
            "max_journal_bytes",
            JOURNAL_BYTES,
            "max_conflicts_returned",
            32);
    c.add("allowed_blocks", JSON.toJsonTree(new TreeSet<>(PALETTE)));
    c.add(
        "supported_block_states",
        JSON.toJsonTree(List.of("allowlisted default inert full blocks; exact air states for restoration")));
    c.add("supported_operation_types", JSON.toJsonTree(List.of("fill_cuboid", "place_blocks")));
    return c;
  }

  JsonObject handle(String action, JsonObject req, String owner, String dim, World world) {
    try {
      if (action.equals("builder_capabilities"))
        return ok(
            "capabilities",
            obj(
                "capabilities",
                capabilities(),
                "world",
                root.world,
                "identity_status",
                currentPath.equals(root.world.original_path) ? "bound" : "moved_or_copied"));
      require(
          healthy,
          "journal_unavailable",
          "Journal write failed; restart and reconcile before further actions");
      if (action.equals("acknowledge_world_move")) {
        require(
            uuid(req, "world_id").equals(root.world.world_id),
            "world_mismatch",
            "Wrong world UUID");
        require(
            str(req, "previous_path").equals(root.world.original_path),
            "identity_mismatch",
            "Previous path must match metadata");
        require(
            str(req, "current_path").equals(currentPath),
            "identity_mismatch",
            "Current path must match actual save path");
        root.world =
            new WorldIdentity(
                root.world.world_id, root.world.seed, root.world.save_name, currentPath);
        for (Project p : root.projects) p.world = root.world;
        previews.clear();
        persist();
        return ok("rebound", obj("world", root.world));
      }
      require(
          currentPath.equals(root.world.original_path),
          "moved_or_copied_world",
          "Explicit acknowledge_world_move required",
          obj("world", root.world, "current_path", currentPath));
      require(
          allowed != null && dimension != null, "build_disabled", "No permitted region configured");
      require(
          dimension.equals(dim), "dimension_mismatch", "Player must be in configured dimension");
      if (action.equals("list_projects"))
        return ok(
            "listed",
            obj(
                "projects",
                root.projects.stream()
                    .filter(p -> p.owner.equals(owner))
                    .map(
                        p ->
                            obj(
                                "project_id",
                                p.project_id,
                                "name",
                                p.name,
                                "state",
                                p.state,
                                "retained_operations",
                                operations(p).size(),
                                "world_verified",
                                false))
                    .toList()));
      if (action.equals("create_project")) return create(req, owner, dim);
      Project p = project(uuid(req, "project_id"), owner);
      require(
          p.dimension.equals(dim) && allowed.contains(p.bounds),
          "outside_allowed_region",
          "Project is outside current permitted region");
      if (action.equals("get_project")) {
        JsonObject observation = reconcile(p, world);
        return ok("inspected", obj("project", publicProject(p), "verification", observation));
      }
      if (action.equals("rollback_project")
          || action.equals("undo_latest_phase")
          || action.equals("recover_project")) return rollback(p, req, action, world);
      JsonObject health = reconcile(p, world);
      require(
          health.get("matches").getAsBoolean(),
          "project_conflict",
          "World and project history require resolution",
          health);
      require(p.state == ProjectState.ACTIVE, "project_not_active", "Project is not active");
      return switch (action) {
        case "start_phase" -> startPhase(p, req);
        case "complete_phase" -> complete(p, req);
        case "preview_build_plan" -> preview(p, req, owner, world);
        case "apply_build_plan" -> apply(p, req, owner, world);
        default -> throw new Failure("invalid_request", "Unknown project action");
      };
    } catch (Failure f) {
      JsonObject r = obj("ok", false, "error_code", f.code, "error", f.getMessage());
      f.details.entrySet().forEach(e -> r.add(e.getKey(), e.getValue()));
      return r;
    } catch (Exception e) {
      return obj(
          "ok",
          false,
          "error_code",
          "invalid_request",
          "error",
          e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private JsonObject create(JsonObject req, String owner, String dim) {
    String id = uuid(req, "project_id"), name = name(req);
    require(
        root.projects.size() < PROJECTS,
        "history_limit",
        "Save project limit reached; no automatic deletion");
    require(
        root.projects.stream().noneMatch(p -> p.project_id.equals(id) || p.name.equals(name)),
        "duplicate_project",
        "Project ID or name already exists");
    Bounds bounds = bounds(req.getAsJsonObject("bounds"));
    require(bounds.volume() <= PROJECT_VOLUME, "size_limit", "Project volume exceeds limit");
    require(allowed.contains(bounds), "outside_allowed_region", "Project exceeds permitted region");
    require(
        root.projects.stream()
            .filter(p -> p.state != ProjectState.ROLLED_BACK)
            .noneMatch(p -> intersects(p.bounds, bounds)),
        "project_overlap",
        "Active project sites may not overlap");
    Project p = new Project();
    p.project_id = id;
    p.name = name;
    p.owner = owner;
    p.dimension = dim;
    p.world = root.world;
    p.bounds = bounds;
    p.limits = capabilities();
    p.created_at = Instant.now().toString();
    root.projects.add(p);
    persist();
    return ok("created", obj("project", publicProject(p)));
  }

  private JsonObject startPhase(Project p, JsonObject req) {
    String id = uuid(req, "phase_id");
    require(p.phases.size() < HISTORY, "history_limit", "Phase limit reached");
    require(
        p.phases.stream()
            .noneMatch(ph -> ph.state == PhaseState.OPEN || ph.state == PhaseState.CONFLICTED),
        "phase_open",
        "Complete or undo current phase first");
    require(
        root.projects.stream()
            .flatMap(pr -> pr.phases.stream())
            .noneMatch(ph -> ph.phase_id.equals(id)),
        "duplicate_phase",
        "Phase ID already exists");
    Phase ph = new Phase();
    ph.phase_id = id;
    ph.name = name(req);
    p.phases.add(ph);
    changed(p);
    return ok("phase_started", obj("phase", ph));
  }

  private JsonObject complete(Project p, JsonObject req) {
    Phase ph = openPhase(p, uuid(req, "phase_id"));
    require(
        !ph.operations.isEmpty() && ph.operations.stream().allMatch(o -> o.state == State.APPLIED),
        "phase_incomplete",
        "Phase needs verified applied operations");
    ph.state = PhaseState.COMPLETE;
    changed(p);
    return ok("phase_complete", obj("phase", publicPhase(ph)));
  }

  static Plan parsePlan(JsonObject raw) {
    fields(raw, "schema", "project_id", "phase_id", "operations");
    require(
        str(raw, "schema").equals(SCHEMA), "unsupported_schema", "Unsupported BuildPlan schema");
    String project = uuid(raw, "project_id"), phase = uuid(raw, "phase_id");
    JsonArray ops = raw.getAsJsonArray("operations");
    require(
        ops != null && !ops.isEmpty() && ops.size() <= PLAN_OPS,
        "size_limit",
        "Plan operation count exceeds limit");
    List<Primitive> result = new ArrayList<>();
    int count = 0;
    for (JsonElement el : ops) {
      JsonObject op = el.getAsJsonObject();
      String type = str(op, "type");
      List<Target> targets = new ArrayList<>();
      if (type.equals("fill_cuboid")) {
        fields(op, "type", "min", "max", "block");
        Bounds b = bounds(op);
        String block = block(op);
        require(b.volume() <= OP_VOLUME, "size_limit", "Fill exceeds 512 positions");
        for (long x = b.min.x; x <= b.max.x; x++)
          for (long y = b.min.y; y <= b.max.y; y++)
            for (long z = b.min.z; z <= b.max.z; z++)
              targets.add(new Target(new Pos((int) x, (int) y, (int) z), block));
      } else if (type.equals("place_blocks")) {
        fields(op, "type", "placements");
        JsonArray items = op.getAsJsonArray("placements");
        require(
            items != null && !items.isEmpty() && items.size() <= EXPLICIT,
            "size_limit",
            "Explicit write count exceeds 64");
        Set<Pos> seen = new HashSet<>();
        for (JsonElement item : items) {
          JsonObject t = item.getAsJsonObject();
          fields(t, "pos", "block");
          Pos pos = pos(t.getAsJsonObject("pos"));
          require(seen.add(pos), "duplicate_position", "Duplicate position");
          targets.add(new Target(pos, block(t)));
        }
        require(
            enclosing(targets.stream().map(Target::pos).toList()).volume() <= EXPLICIT,
            "size_limit",
            "Explicit bounding volume exceeds 64");
      } else
        throw new Failure(
            "unsupported_operation", "Only fill_cuboid and place_blocks are supported");
      targets.sort(Comparator.comparing(Target::pos));
      result.add(new Primitive(type, List.copyOf(targets)));
      count += targets.size();
    }
    require(count <= WRITES, "size_limit", "Total plan writes exceed 512");
    return new Plan(SCHEMA, project, phase, List.copyOf(result));
  }

  private JsonObject preview(Project p, JsonObject req, String owner, World world) {
    Plan plan = parsePlan(req.getAsJsonObject("plan"));
    require(
        plan.project_id.equals(p.project_id),
        "project_mismatch",
        "Plan project differs from request");
    openPhase(p, plan.phase_id);
    require(
        operations(p).size() + plan.operations.size() <= HISTORY,
        "history_limit",
        "Retained operation limit reached (including undone records)");
    plan.operations.stream().flatMap(op -> op.targets.stream())
        .map(Target::block).distinct().forEach(world::validateState);
    TreeMap<Pos, String> initial = new TreeMap<>(), virtual = new TreeMap<>();
    List<List<Write>> all = new ArrayList<>();
    TreeMap<String, Integer> overwritten = new TreeMap<>(), palette = new TreeMap<>();
    int changes = 0;
    for (Primitive op : plan.operations) {
      List<Write> writes = new ArrayList<>();
      int changed = 0;
      for (Target t : op.targets) {
        require(
            p.bounds.contains(t.pos), "outside_project_bounds", "Operation exceeds project site");
        String current = initial.computeIfAbsent(t.pos, world::read),
            before = virtual.getOrDefault(t.pos, current);
        require(
            isAir(before) || before.equals(t.block),
            "overwrite_not_allowed",
            "V1 builds only into air or identical masonry");
        if (!before.equals(t.block)) {
          changed++;
          overwritten.merge(before, 1, Integer::sum);
        }
        palette.merge(t.block, 1, Integer::sum);
        writes.add(new Write(t.pos, before, t.block));
        virtual.put(t.pos, t.block);
      }
      require(changed > 0, "no_changes", "Each operation must change at least one block");
      changes += changed;
      all.add(List.copyOf(writes));
    }
    previews.values().removeIf(v -> v.expires < System.nanoTime());
    require(previews.size() < 8, "busy", "Preview capacity reached");
    String id = UUID.randomUUID().toString(), hash = hash(plan), digest = digest(initial);
    Preview v =
        new Preview(
            id,
            owner,
            p.dimension,
            plan,
            hash,
            digest,
            p.revision,
            System.nanoTime() + 60_000_000_000L,
            List.copyOf(all),
            Map.copyOf(initial));
    previews.put(id, v);
    return ok(
        "previewed",
        obj(
            "preview_id",
            id,
            "plan_hash",
            hash,
            "project_id",
            p.project_id,
            "phase_id",
            plan.phase_id,
            "bounds",
            enclosing(new ArrayList<>(initial.keySet())),
            "operation_count",
            all.size(),
            "block_write_count",
            all.stream().mapToInt(List::size).sum(),
            "expected_changed_blocks",
            changes,
            "affected_existing_blocks",
            overwritten,
            "palette",
            palette,
            "pre_state_digest",
            digest,
            "chunks_loaded",
            true,
            "touches_outside_allowed_region",
            false,
            "block_entities_affected",
            0,
            "expires_in_seconds",
            60));
  }

  private JsonObject apply(Project p, JsonObject req, String owner, World world) throws Exception {
    Preview v = previews.get(uuid(req, "preview_id"));
    require(
        v != null && v.expires >= System.nanoTime(),
        "preview_unavailable",
        "Preview expired or already consumed");
    require(
        str(req, "plan_hash").equals(v.hash), "plan_hash_mismatch", "Exact preview hash required");
    require(
        v.owner.equals(owner)
            && v.plan.project_id.equals(p.project_id)
            && v.dimension.equals(p.dimension),
        "preview_mismatch",
        "Preview belongs to another project/player");
    require(v.revision == p.revision, "stale_preview", "Project or phase changed; preview again");
    Phase ph = openPhase(p, v.plan.phase_id);
    for (var e : v.pre.entrySet())
      require(
          world.read(e.getKey()).equals(e.getValue()),
          "stale_preview",
          "Target block state changed; preview again");
    require(
        operations(p).size() + v.writes.size() <= HISTORY, "history_limit", "Project history full");
    v.writes.stream().flatMap(List::stream)
        .flatMap(w -> java.util.stream.Stream.of(w.before, w.after))
        .distinct().forEach(world::validateState);
    previews.remove(v.id);
    List<String> ids = new ArrayList<>();
    for (List<Write> writes : v.writes) {
      Operation o = new Operation();
      o.operation_id = UUID.randomUUID().toString();
      o.project_id = p.project_id;
      o.phase_id = ph.phase_id;
      o.world_id = root.world.world_id;
      o.dimension = p.dimension;
      o.plan_hash = v.hash;
      o.created_at = Instant.now().toString();
      o.writes = writes;
      o.bounds = enclosing(writes.stream().map(Write::pos).toList());
      o.changed_blocks = (int) writes.stream().filter(w -> !w.before.equals(w.after)).count();
      o.pre_state_digest = hash(writes.stream().map(w -> new Target(w.pos, w.before)).toList());
      o.post_state_digest = hash(writes.stream().map(w -> new Target(w.pos, w.after)).toList());
      ph.operations.add(o);
      p.revision++;
      persist();
      o.state = State.APPLYING;
      persist();
      ids.add(o.operation_id);
      try {
        world.write(writes, false);
        require(
            matches(writes, world, false), "verification_failed", "Actual blocks differ from plan");
        world.checkpoint();
        o.state = State.APPLIED;
        persist();
      } catch (Exception failure) {
        o.recovery_from = State.APPLYING;
        o.state = State.UNKNOWN;
        p.state = ProjectState.CONFLICTED;
        if (healthy) persist();
        throw new Failure(
            "completion_unknown",
            "Apply needs reconciliation: " + failure.getMessage(),
            obj("operation_ids", ids, "project_id", p.project_id));
      }
    }
    return ok(
        "applied",
        obj(
            "operation_ids",
            ids,
            "plan_hash",
            v.hash,
            "world_verified",
            true,
            "journal_persisted",
            true,
            "project",
            publicProject(p)));
  }

  private JsonObject rollback(Project p, JsonObject req, String action, World world)
      throws Exception {
    JsonObject reconciliation = reconcile(p, world);
    List<Phase> applicable = p.phases.stream().filter(ph -> ph.state != PhaseState.UNDONE).toList();
    if (applicable.isEmpty()) {
      require(action.equals("rollback_project"), "empty_history", "No phase remains");
      require(
          reconciliation.get("matches").getAsBoolean(),
          "rollback_conflict",
          "Previously restored positions have changed",
          reconciliation);
      p.state = ProjectState.ROLLED_BACK;
      changed(p);
      return ok(
          "rolled_back",
          obj("project", publicProject(p), "world_verified", true, "journal_persisted", true));
    }
    Phase latest = applicable.getLast();
    if (action.equals("undo_latest_phase"))
      require(
          uuid(req, "phase_id").equals(latest.phase_id),
          "out_of_order",
          "Only newest phase can be undone");
    boolean recovery = action.equals("recover_project");
    if (recovery)
      require(
          str(req, "mode").equals("rollback_incomplete"),
          "invalid_request",
          "Explicit rollback_incomplete recovery mode required");
    List<Phase> phases =
        new ArrayList<>(action.equals("undo_latest_phase") ? List.of(latest) : applicable);
    Collections.reverse(phases);
    List<Operation> reverse = new ArrayList<>();
    for (Phase ph : phases)
      for (Operation o : ph.operations.reversed())
        if (o.state != State.UNDONE && o.state != State.FAILED) reverse.add(o);
    reverse.stream().flatMap(o -> o.writes.stream())
        .flatMap(w -> java.util.stream.Stream.of(w.before, w.after))
        .distinct().forEach(world::validateState);
    TreeMap<Pos, String> virtual = new TreeMap<>();
    List<JsonObject> conflicts = new ArrayList<>();
    int conflictCount = 0;
    // Preflight the entire rollback against a virtual reversed world before changing any block.
    for (Operation o : reverse) {
      boolean partial =
          o.state == State.UNKNOWN
              && (o.recovery_from == State.APPLYING
                  || o.recovery_from == State.UNDOING
                  || o.recovery_from == State.PREPARED);
      require(
          !partial || recovery,
          "recovery_required",
          "Incomplete operation needs explicit rollback_incomplete acknowledgement",
          obj("operation_id", o.operation_id));
      for (Write w : o.writes) {
        String current = virtual.computeIfAbsent(w.pos, world::read);
        if (!current.equals(w.after) && !(partial && current.equals(w.before))) {
          conflictCount++;
          if (conflicts.size() < 32)
            conflicts.add(
                obj(
                    "pos",
                    w.pos,
                    "expected",
                    w.after,
                    "current",
                    current,
                    "operation_id",
                    o.operation_id));
        }
        virtual.put(w.pos, w.before);
      }
    }
    // Also protect positions touched only by already-undone work.
    Map<Pos, String> original = new TreeMap<>();
    for (Operation o : operations(p))
      for (Write w : o.writes) original.putIfAbsent(w.pos, w.before);
    if (!action.equals("undo_latest_phase"))
      for (var e : original.entrySet())
        if (!virtual.containsKey(e.getKey())) {
          String actual = world.read(e.getKey());
          if (!actual.equals(e.getValue())) {
            conflictCount++;
            if (conflicts.size() < 32)
              conflicts.add(obj("pos", e.getKey(), "expected", e.getValue(), "current", actual));
          }
        }
    if (conflictCount > 0) {
      p.state = ProjectState.CONFLICTED;
      changed(p);
      throw new Failure(
          "rollback_conflict",
          "External changes must be explicitly resolved before rollback",
          obj("conflict_count", conflictCount, "conflicts", conflicts, "blocks_changed", 0));
    }
    p.state = ProjectState.ROLLING_BACK;
    changed(p);
    for (Phase ph : phases) {
      for (Operation o : ph.operations.reversed()) {
        if (o.state == State.UNDONE || o.state == State.FAILED) continue;
        o.state = State.UNDOING;
        persist();
        try {
          world.write(o.writes, true);
          require(
              matches(o.writes, world, true), "verification_failed", "Restore verification failed");
          world.checkpoint();
          o.state = State.UNDONE;
          o.recovery_from = null;
          o.recovery_observation = "original_states_verified";
          p.undo_order.add(o.operation_id);
          persist();
        } catch (Exception e) {
          o.recovery_from = State.UNDOING;
          o.state = State.UNKNOWN;
          p.state = ProjectState.CONFLICTED;
          if (healthy) persist();
          throw new Failure("completion_unknown", "Undo needs reconciliation");
        }
      }
      ph.state = PhaseState.UNDONE;
      persist();
    }
    p.state = action.equals("undo_latest_phase") ? ProjectState.ACTIVE : ProjectState.ROLLED_BACK;
    changed(p);
    return ok(
        action.equals("undo_latest_phase") ? "phase_undone" : "rolled_back",
        obj("world_verified", true, "journal_persisted", true, "project", publicProject(p)));
  }

  /** Resolves only evidence, never writes blocks. Partial/unexpected observations stay blocked. */
  JsonObject reconcile(Project p, World world) {
    boolean changed = false, ambiguous = false;
    for (Operation o : operations(p)) {
      State state = o.state == State.UNKNOWN ? o.recovery_from : o.state;
      if (state == State.PREPARED || state == State.APPLYING || state == State.UNDOING) {
        int before = 0, after = 0, other = 0;
        for (Write w : o.writes) {
          String current = world.read(w.pos);
          if (current.equals(w.before)) before++;
          if (current.equals(w.after)) after++;
          if (!current.equals(w.before) && !current.equals(w.after)) other++;
        }
        o.recovery_observation =
            other > 0
                ? "inconsistent"
                : before == o.writes.size()
                    ? "unapplied"
                    : after == o.writes.size() ? "fully_applied" : "partial";
        State decision = recoveryDecision(state, before, after, other, o.writes.size());
        if (decision != o.state) {
          o.recovery_from = state;
          o.state = decision;
          changed = true;
        }
        ambiguous |= decision == State.UNKNOWN;
      }
    }
    Map<Pos, String> expected = expected(p);
    List<JsonObject> conflicts = new ArrayList<>();
    int count = 0;
    for (var e : expected.entrySet()) {
      String actual = world.read(e.getKey());
      if (!e.getValue().equals(actual)) {
        count++;
        if (conflicts.size() < 32)
          conflicts.add(obj("pos", e.getKey(), "expected", e.getValue(), "current", actual));
      }
    }
    boolean matches = count == 0 && !ambiguous;
    if (!matches && p.state != ProjectState.CONFLICTED) {
      p.state = ProjectState.CONFLICTED;
      changed = true;
    }
    // A repaired external conflict is healthy again. Retained phase states were never discarded.
    if (matches && p.state == ProjectState.CONFLICTED) {
      p.state =
          p.phases.stream().allMatch(ph -> ph.state == PhaseState.UNDONE)
              ? ProjectState.ROLLED_BACK
              : ProjectState.ACTIVE;
      changed = true;
    }
    if (changed) {
      p.revision++;
      persist();
    }
    return obj(
        "matches",
        matches,
        "positions_checked",
        expected.size(),
        "conflict_count",
        count,
        "conflicts",
        conflicts,
        "incomplete_operations",
        ambiguous);
  }

  static State recoveryDecision(State state, int before, int after, int other, int total) {
    if (other > 0) return State.UNKNOWN;
    if (state == State.UNDOING) return before == total ? State.UNDONE : State.UNKNOWN;
    if (state == State.PREPARED || state == State.APPLYING) {
      if (after == total) return State.APPLIED;
      if (before == total) return State.FAILED;
      return State.UNKNOWN;
    }
    return state;
  }

  static Map<Pos, String> expected(Project p) {
    Map<Pos, String> map = new TreeMap<>();
    for (Operation o : operations(p)) for (Write w : o.writes) map.putIfAbsent(w.pos, w.before);
    for (Operation o : operations(p))
      if (o.state != State.UNDONE && o.state != State.FAILED)
        for (Write w : o.writes) map.put(w.pos, w.after);
    return map;
  }

  static JsonObject publicProject(Project p) {
    JsonObject result = JSON.toJsonTree(p).getAsJsonObject();
    JsonArray phases = new JsonArray();
    p.phases.forEach(ph -> phases.add(publicPhase(ph)));
    result.add("phases", phases);
    result.addProperty("retained_operations", operations(p).size());
    result.addProperty(
        "active_operations",
        operations(p).stream()
            .filter(o -> o.state != State.UNDONE && o.state != State.FAILED)
            .count());
    return result;
  }

  static JsonObject publicPhase(Phase ph) {
    JsonObject r = JSON.toJsonTree(ph).getAsJsonObject();
    for (JsonElement o : r.getAsJsonArray("operations")) o.getAsJsonObject().remove("writes");
    return r;
  }

  static List<Operation> operations(Project p) {
    return p.phases.stream().flatMap(ph -> ph.operations.stream()).toList();
  }

  private Project project(String id, String owner) {
    Project p =
        root.projects.stream()
            .filter(pr -> pr.project_id.equals(id))
            .findFirst()
            .orElseThrow(() -> new Failure("project_missing", "Project not found"));
    require(p.owner.equals(owner), "owner_mismatch", "Project belongs to another player");
    return p;
  }

  private static Phase openPhase(Project p, String id) {
    return p.phases.stream()
        .filter(ph -> ph.phase_id.equals(id) && ph.state == PhaseState.OPEN)
        .findFirst()
        .orElseThrow(() -> new Failure("no_open_phase", "No matching open phase"));
  }

  private void changed(Project p) {
    p.revision++;
    persist();
  }

  private void persist() {
    try {
      store.save(root);
    } catch (Exception e) {
      healthy = false;
      throw new Failure(
          "journal_unavailable", "Atomic journal persistence failed: " + e.getMessage());
    }
  }

  static boolean matches(List<Write> writes, World world, boolean undo) {
    return writes.stream().allMatch(w -> world.read(w.pos).equals(undo ? w.before : w.after));
  }

  static boolean isAir(String state) {
    return Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(state);
  }

  static Bounds bounds(JsonObject o) {
    return new Bounds(pos(o.getAsJsonObject("min")), pos(o.getAsJsonObject("max")));
  }

  static Pos pos(JsonObject o) {
    fields(o, "x", "y", "z");
    return new Pos(integer(o, "x"), integer(o, "y"), integer(o, "z"));
  }

  static int integer(JsonObject o, String key) {
    JsonElement e = o.get(key);
    require(
        e != null
            && e.isJsonPrimitive()
            && e.getAsJsonPrimitive().isNumber()
            && e.getAsString().matches("-?(0|[1-9][0-9]*)"),
        "invalid_coordinate",
        "Integer coordinates required");
    try {
      return Integer.parseInt(e.getAsString());
    } catch (Exception x) {
      throw new Failure("invalid_coordinate", "Coordinate outside integer range");
    }
  }

  static String str(JsonObject o, String k) {
    require(
        o != null
            && o.has(k)
            && o.get(k).isJsonPrimitive()
            && o.get(k).getAsJsonPrimitive().isString(),
        "invalid_request",
        "String required: " + k);
    return o.get(k).getAsString();
  }

  static String uuid(JsonObject o, String k) {
    String s = str(o, k);
    require(
        s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        "invalid_id",
        "Canonical UUID required: " + k);
    return s;
  }

  static String name(JsonObject o) {
    String n = str(o, "name");
    require(
        n.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,79}"),
        "invalid_name",
        "Name must be 1..80 simple characters");
    return n;
  }

  static String block(JsonObject o) {
    String b = str(o, "block");
    require(PALETTE.contains(b), "block_not_allowed", "Unsupported block/state");
    return b;
  }

  static void fields(JsonObject o, String... names) {
    require(
        o != null && o.keySet().equals(Set.of(names)),
        "invalid_request",
        "Unexpected or missing fields");
  }

  static Bounds enclosing(List<Pos> points) {
    int x = Integer.MAX_VALUE, y = x, z = x, X = Integer.MIN_VALUE, Y = X, Z = X;
    for (Pos p : points) {
      x = Math.min(x, p.x);
      y = Math.min(y, p.y);
      z = Math.min(z, p.z);
      X = Math.max(X, p.x);
      Y = Math.max(Y, p.y);
      Z = Math.max(Z, p.z);
    }
    return new Bounds(new Pos(x, y, z), new Pos(X, Y, Z));
  }

  static boolean intersects(Bounds a, Bounds b) {
    return a.min.x <= b.max.x
        && a.max.x >= b.min.x
        && a.min.y <= b.max.y
        && a.max.y >= b.min.y
        && a.min.z <= b.max.z
        && a.max.z >= b.min.z;
  }

  static String digest(Map<Pos, String> map) {
    return hash(
        new TreeMap<>(map)
            .entrySet().stream().map(e -> new Target(e.getKey(), e.getValue())).toList());
  }

  static String hash(Object data) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.toJson(data).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonObject obj(Object... pairs) {
    JsonObject o = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2)
      o.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
    return o;
  }

  static JsonObject ok(String status, JsonObject data) {
    data.addProperty("ok", true);
    data.addProperty("status", status);
    return data;
  }

  static void require(boolean b, String code, String message) {
    if (!b) throw new Failure(code, message);
  }

  static void require(boolean b, String code, String message, JsonObject detail) {
    if (!b) throw new Failure(code, message, detail);
  }

  static void validateRoot(Root root) {
    require(
        PROTOCOL.equals(root.protocol_version)
            && root.projects != null
            && root.projects.size() <= PROJECTS,
        "invalid_journal",
        "Unsupported journal");
    Set<String> ids = new HashSet<>();
    for (Project p : root.projects) {
      uuid(obj("id", p.project_id), "id");
      require(
          ids.add(p.project_id)
              && p.state != null
              && p.phases != null
              && p.phases.size() <= HISTORY
              && p.bounds.volume() <= PROJECT_VOLUME
              && p.world.equals(root.world),
          "invalid_journal",
          "Invalid project metadata");
      require(operations(p).size() <= HISTORY, "invalid_journal", "Too many retained operations");
      for (Phase ph : p.phases) {
        uuid(obj("id", ph.phase_id), "id");
        require(ids.add(ph.phase_id) && ph.state != null, "invalid_journal", "Invalid phase");
        for (Operation o : ph.operations) {
          uuid(obj("id", o.operation_id), "id");
          require(
              ids.add(o.operation_id)
                  && o.state != null
                  && o.project_id.equals(p.project_id)
                  && o.phase_id.equals(ph.phase_id)
                  && o.world_id.equals(root.world.world_id)
                  && o.dimension.equals(p.dimension),
              "invalid_journal",
              "Invalid ownership");
          require(
              o.writes != null
                  && !o.writes.isEmpty()
                  && o.writes.size() <= WRITES
                  && o.bounds.volume() <= OP_VOLUME
                  && p.bounds.contains(o.bounds),
              "invalid_journal",
              "Invalid snapshots");
          Set<Pos> seen = new HashSet<>();
          for (Write w : o.writes)
            require(
                seen.add(w.pos)
                    && o.bounds.contains(w.pos)
                    && PALETTE.contains(w.after)
                    && (isAir(w.before) || w.before.equals(w.after)),
                "invalid_journal",
                "Unsafe stored state");
          require(
              o.pre_state_digest.equals(
                      hash(o.writes.stream().map(w -> new Target(w.pos, w.before)).toList()))
                  && o.post_state_digest.equals(
                      hash(o.writes.stream().map(w -> new Target(w.pos, w.after)).toList())),
              "invalid_journal",
              "Snapshot digest mismatch");
        }
      }
    }
  }

  static final class FileStore implements Store {
    final Path path;

    FileStore(Path path) {
      this.path = path;
    }

    public Root load() {
      if (!Files.exists(path)) return null;
      try {
        require(Files.size(path) <= JOURNAL_BYTES, "invalid_journal", "Journal exceeds limit");
        JsonObject envelope = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        JsonObject payload = envelope.getAsJsonObject("payload");
        require(
            str(envelope, "sha256").equals(hash(payload)),
            "invalid_journal",
            "Journal checksum mismatch");
        Root r = JSON.fromJson(payload, Root.class);
        validateRoot(r);
        return r;
      } catch (Failure f) {
        throw f;
      } catch (Exception e) {
        throw new Failure("invalid_journal", e.getMessage());
      }
    }

    public void save(Root root) {
      atomic(path, obj("sha256", hash(root), "payload", root), JOURNAL_BYTES);
    }
  }

  static void atomic(Path path, Object data, int max) {
    try {
      byte[] bytes = JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
      require(bytes.length <= max, "journal_limit", "Persistence size limit exceeded");
      Files.createDirectories(path.getParent());
      Path temp = path.resolveSibling(path.getFileName() + ".tmp");
      try (FileChannel channel =
          FileChannel.open(
              temp,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      // Fail closed on file systems without atomic replacement. Never erase the last good record.
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Failure f) {
      throw f;
    } catch (Exception e) {
      throw new Failure("journal_unavailable", e.getMessage());
    }
  }
}
