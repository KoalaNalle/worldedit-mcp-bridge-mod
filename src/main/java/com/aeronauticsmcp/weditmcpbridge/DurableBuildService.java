package com.aeronauticsmcp.weditmcpbridge;

import static com.aeronauticsmcp.weditmcpbridge.BuilderEngine.*;

import com.google.gson.*;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Thin game-thread adapter; all project decisions and recovery live in the pure engine. */
final class DurableBuildService {
  private final MinecraftServer server;
  private BuilderEngine engine;
  private String startupError;
  private Path legacyPath;

  DurableBuildService(MinecraftServer server) {
    this.server = server;
    try {
      Path path = server.getWorldPath(LevelResource.ROOT).toRealPath();
      Path data = path.resolve("data");
      Path identityPath = data.resolve("weditmcpbridge-world.json");
      Path journal = data.resolve("weditmcpbridge-projects.json");
      legacyPath = data.resolve("weditmcpbridge-operations.json");
      WorldIdentity identity;
      if (Files.exists(identityPath)) {
        require(
            Files.size(identityPath) < 4096,
            "invalid_identity",
            "World identity metadata oversized");
        identity = JSON.fromJson(Files.readString(identityPath), WorldIdentity.class);
        uuid(obj("id", identity.world_id()), "id");
        require(
            identity.seed().equals(Long.toString(server.overworld().getSeed())),
            "world_mismatch",
            "Stored builder identity seed differs");
      } else {
        require(
            !Files.exists(journal),
            "missing_identity",
            "Journal exists without identity metadata; administrative repair required");
        identity =
            new WorldIdentity(
                UUID.randomUUID().toString(),
                Long.toString(server.overworld().getSeed()),
                server.getWorldData().getLevelName(),
                path.toString());
        atomic(identityPath, identity, 4096);
      }
      String dim = null;
      Bounds bounds = null;
      String raw = System.getenv("WEDIT_BRIDGE_ALLOWED_REGION");
      if (raw != null && !raw.isBlank()) {
        String[] fields = raw.split(";", -1);
        require(
            fields.length == 3 && ResourceLocation.tryParse(fields[0]) != null,
            "invalid_region",
            "Invalid configured region");
        dim = fields[0];
        bounds = new Bounds(point(fields[1]), point(fields[2]));
        bounds.volume();
      }
      engine =
          new BuilderEngine(
              new BuilderEngine.FileStore(journal), identity, path.toString(), dim, bounds);
    } catch (Exception e) {
      startupError = e.getMessage();
      WeditMcpBridge.LOGGER.error("Durable builder disabled: {}", startupError);
    }
  }

  private static Pos point(String text) {
    String[] s = text.split(",");
    require(s.length == 3, "invalid_region", "Region needs x,y,z");
    return new Pos(Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2]));
  }

  JsonObject handle(String action, ServerPlayer player, JsonObject request) {
    if (engine == null)
      return obj("ok", false, "error_code", "journal_unavailable", "error", startupError);
    if (!player.hasPermissions(2))
      return obj(
          "ok",
          false,
          "error_code",
          "permission_denied",
          "error",
          "Builder requires operator permission");
    try {
      if (action.equals("create_project") && Files.exists(legacyPath)) {
        JsonElement pending =
            JsonParser.parseString(Files.readString(legacyPath)).getAsJsonObject().get("pending");
        require(
            pending == null || pending.isJsonNull(),
            "legacy_undo_pending",
            "Recover/undo the previous protocol operation before creating a project");
      }
      JsonObject result = engine.handle(
          action,
          request,
          player.getUUID().toString(),
          player.serverLevel().dimension().location().toString(),
          new World() {
            public void validateState(String state) {
              validatedState(state);
            }

            public String read(Pos pos) {
              var level = player.serverLevel();
              BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
              require(
                  pos.y() >= level.getMinBuildHeight() && pos.y() < level.getMaxBuildHeight(),
                  "outside_world_height",
                  "Position exceeds world height");
              require(
                  level.getChunkSource().getChunkNow(pos.x() >> 4, pos.z() >> 4) != null,
                  "chunk_unloaded",
                  "Target chunk is not loaded");
              require(
                  level.getBlockEntity(p) == null,
                  "block_entity_conflict",
                  "Block entities are unsupported");
              return NeoForgeAdapter.adapt(level.getBlockState(p)).getAsString();
            }

            public void write(List<Write> writes, boolean undo) throws Exception {
              // Recheck loaded/block-entity state immediately before writes; no network wait here.
              Map<String, BlockState> states = new HashMap<>();
              for (Write w : writes) {
                read(w.pos());
                String target = undo ? w.before() : w.after();
                states.computeIfAbsent(target, DurableBuildService::validatedState);
              }
              try (EditSession edit =
                  WorldEdit.getInstance()
                      .newEditSessionBuilder()
                      .world(NeoForgeAdapter.adapt(player.serverLevel()))
                      .actor(NeoForgeAdapter.adaptPlayer(player))
                      .maxBlocks(WRITES)
                      .build()) {
                edit.setReorderMode(EditSession.ReorderMode.NONE);
                for (Write w : writes) {
                  String state = undo ? w.before() : w.after();
                  BlockState block = states.get(state);
                  edit.setBlock(
                      BlockVector3.at(w.pos().x(), w.pos().y(), w.pos().z()),
                      NeoForgeAdapter.adapt(block));
                }
              }
            }

            public void checkpoint() {
              server.saveEverything(true, true, true);
            }
          });
      if (action.equals("builder_capabilities") && result.get("ok").getAsBoolean()) {
        JsonArray metadata = new JsonArray();
        new TreeSet<>(PALETTE).forEach(id -> metadata.add(inspectPalette(id)));
        result.getAsJsonObject("capabilities").add("palette_metadata", metadata);
      }
      return result;
    } catch (Failure f) {
      return obj("ok", false, "error_code", f.code, "error", f.getMessage());
    } catch (Exception e) {
      return obj("ok", false, "error_code", "journal_unavailable", "error", e.getMessage());
    }
  }
  private static PaletteSafety.Facts facts(String id, BlockState state) {
    return new PaletteSafety.Facts(true,
        NeoForgeAdapter.adapt(state).getAsString().equals(id), state.getProperties().isEmpty(),
        state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
        state.hasBlockEntity(), !state.getFluidState().isEmpty(),
        state.getBlock() instanceof FallingBlock, state.isRandomlyTicking(), state.isSignalSource());
  }

  private static BlockState validatedState(String text) {
    require(PALETTE.contains(text) || isAir(text), "block_not_allowed", "Unsupported block/state");
    ResourceLocation id = ResourceLocation.tryParse(text);
    require(id != null && BuiltInRegistries.BLOCK.containsKey(id), "unsupported_state",
        "Registry block missing: " + text);
    BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    require(NeoForgeAdapter.adapt(state).getAsString().equals(text), "unsupported_state",
        "Full stored state differs from default: " + text);
    // Exact air states are restoration-only and intentionally do not have full collision.
    if (isAir(text)) return state;
    List<String> reasons = PaletteSafety.reasons(facts(text, state));
    require(reasons.isEmpty(), "unsupported_state", "Unsafe palette block " + text + ": " + reasons);
    return state;
  }

  private static JsonObject inspectPalette(String text) {
    ResourceLocation id = ResourceLocation.tryParse(text);
    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
      return obj("id", text, "source_mod", id == null ? "" : id.getNamespace(),
          "registered", false, "safe", false, "safety_reasons", List.of("registry_missing"));
    BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    List<String> reasons = PaletteSafety.reasons(facts(text, state));
    return obj("id", text, "display_name", state.getBlock().getName().getString(),
        "source_mod", id.getNamespace(), "block_class", state.getBlock().getClass().getName(),
        "default_state", NeoForgeAdapter.adapt(state).getAsString(), "registered", true,
        "light_emission", state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
        "safe", reasons.isEmpty(), "safety_reasons", reasons);
  }
}
