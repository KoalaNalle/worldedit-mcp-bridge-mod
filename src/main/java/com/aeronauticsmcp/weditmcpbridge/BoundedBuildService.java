package com.aeronauticsmcp.weditmcpbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A tiny, server-thread-only WorldEdit path. Writes are disabled without an explicit region. */
final class BoundedBuildService {
    private static final int MAX_WRITES = 64;
    private static final int MAX_VOLUME = 64;
    private static final int MAX_PREVIEWS = 8;
    private static final long PREVIEW_TTL_NANOS = 60_000_000_000L;
    // Full inert blocks only: no gravity, inventories, scheduled behavior, or block entities.
    private static final Set<String> SAFE_BLOCKS = Set.of("minecraft:stone_bricks",
            "minecraft:sandstone", "minecraft:smooth_sandstone",
            "minecraft:cut_sandstone", "minecraft:chiseled_sandstone");
    private static final Comparator<Write> POSITION_ORDER = Comparator
            .comparingInt((Write write) -> write.pos.getX())
            .thenComparingInt(write -> write.pos.getY())
            .thenComparingInt(write -> write.pos.getZ());

    private final BuildRegion allowedRegion;
    // This object is only accessed from Minecraft's server thread.
    private final Map<String, Preview> previews = new HashMap<>();
    // One outstanding operation prevents history eviction and makes targeted undo unambiguous.
    private Operation pendingUndo;

    BoundedBuildService() {
        allowedRegion = parseAllowedRegion(System.getenv("WEDIT_BRIDGE_ALLOWED_REGION"));
        if (allowedRegion == null) {
            WeditMcpBridge.LOGGER.info("Bounded WorldEdit writes disabled; WEDIT_BRIDGE_ALLOWED_REGION not configured");
        } else {
            WeditMcpBridge.LOGGER.warn("Bounded WorldEdit writes enabled for {} from {} to {}",
                    allowedRegion.dimension, allowedRegion.min, allowedRegion.max);
        }
    }

    JsonObject preview(ServerPlayer player, JsonObject request) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        if (pendingUndo != null) return error("undo_pending", "Undo the previous operation before another preview");
        JsonElement blocksElement = request.get("blocks");
        if (blocksElement == null || !blocksElement.isJsonArray()) {
            return error("invalid_request", "blocks must be an array of explicit block writes");
        }
        JsonArray blocks = blocksElement.getAsJsonArray();
        if (blocks.size() == 0 || blocks.size() > MAX_WRITES) {
            return error("size_limit", "Provide between 1 and 64 block writes");
        }

        ServerLevel level = player.serverLevel();
        List<Write> writes = new ArrayList<>(blocks.size());
        Set<BlockPos> seen = new HashSet<>();
        Set<String> targetPalette = new HashSet<>();
        Map<String, Integer> overwrittenPalette = new LinkedHashMap<>();
        JsonArray overwrittenBlockEntities = new JsonArray();
        Bounds bounds = null;
        int expectedChanged = 0;
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) return error("invalid_request", "Each write must be an object");
            JsonObject item = element.getAsJsonObject();
            Integer x = integerField(item, "x");
            Integer y = integerField(item, "y");
            Integer z = integerField(item, "z");
            String id = stringField(item, "block");
            if (x == null || y == null || z == null || id == null) {
                return error("invalid_request", "Each write needs integer x/y/z and a block registry ID");
            }
            if (!SAFE_BLOCKS.contains(id)) {
                return error("block_not_allowed", "Only the fixed inert masonry palette is allowed: " + id);
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!seen.add(pos)) return error("invalid_request", "Duplicate block position: " + pos);
            if (!allowedRegion.contains(pos) || y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                return error("outside_allowed_region", "Block is outside configured region or world height: " + pos);
            }
            if (!loaded(level, pos)) return error("chunk_unloaded", "Target chunk is not loaded");
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
                return error("invalid_block", "Unknown block registry ID: " + id);
            }
            BlockState desired = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
            if (desired.hasBlockEntity()) return error("block_not_allowed", "Target has a block entity");
            BlockState before = level.getBlockState(pos);
            if (level.getBlockEntity(pos) != null) {
                JsonObject affected = point(pos);
                affected.addProperty("block", stateId(before));
                overwrittenBlockEntities.add(affected);
            }
            if (!before.equals(desired)) expectedChanged++;
            overwrittenPalette.merge(stateId(before), 1, Integer::sum);
            targetPalette.add(id);
            writes.add(new Write(pos, desired, before));
            bounds = bounds == null ? new Bounds(pos) : bounds.include(pos);
        }
        if (bounds == null || bounds.volume() > MAX_VOLUME) {
            return error("size_limit", "Write bounding volume exceeds 64 blocks");
        }
        writes.sort(POSITION_ORDER);
        if (overwrittenBlockEntities.size() > 0) {
            JsonObject response = error("block_entity_conflict", "Preview would overwrite block entities");
            response.add("bounds", bounds.toJson());
            response.add("overwritten_block_entities", overwrittenBlockEntities);
            return response;
        }
        for (Write write : writes) {
            if (!write.before.isAir() && !write.before.equals(write.desired)) {
                JsonObject response = error("overwrite_not_allowed", "This prototype only places blocks into air");
                response.add("bounds", bounds.toJson());
                response.add("overwritten_palette", counts(overwrittenPalette));
                response.add("overwritten_block_entities", overwrittenBlockEntities);
                return response;
            }
        }
        if (expectedChanged == 0) return error("no_changes", "All requested blocks already match");
        purgeExpired();
        if (previews.size() >= MAX_PREVIEWS) return error("busy", "Preview capacity reached; retry in 60 seconds");

        String id = UUID.randomUUID().toString();
        String fingerprint = fingerprint(allowedRegion.dimension, writes, false);
        previews.put(id, new Preview(id, player.getGameProfile().getName(), allowedRegion.dimension,
                System.nanoTime() + PREVIEW_TTL_NANOS, List.copyOf(writes), bounds, fingerprint));
        JsonObject result = success("previewed", false);
        result.addProperty("preview_id", id);
        result.addProperty("expires_at", Instant.now().toEpochMilli() + 60_000);
        result.addProperty("operation_type", "set_blocks");
        result.add("bounds", bounds.toJson());
        result.addProperty("inspected_blocks", writes.size());
        result.addProperty("expected_changed_blocks", expectedChanged);
        result.addProperty("max_changed_blocks", MAX_WRITES);
        result.addProperty("pre_state_fingerprint", fingerprint);
        result.addProperty("chunks_loaded", true);
        result.addProperty("touches_outside_allowed_region", false);
        result.add("target_palette", strings(targetPalette));
        result.add("overwritten_palette", counts(overwrittenPalette));
        result.add("overwritten_block_entities", overwrittenBlockEntities);
        return result;
    }

    JsonObject apply(ServerPlayer player, JsonObject request) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        if (pendingUndo != null) return error("undo_pending", "Undo the previous operation first");
        String previewId = stringField(request, "preview_id");
        if (previewId == null || previewId.length() > 64) return error("invalid_request", "Invalid preview_id");
        // A preview is single-use, including a failed validation. Re-preview to retry.
        Preview preview = previews.remove(previewId);
        if (preview == null) return error("preview_unavailable", "Preview is missing, expired, or already used");
        if (System.nanoTime() - preview.expiresAtNanos >= 0) return error("preview_expired", "Preview expired");
        if (!preview.username.equals(player.getGameProfile().getName())) {
            return error("player_mismatch", "Preview belongs to another player");
        }
        if (!preview.dimension.equals(dimension(player))) {
            return error("dimension_changed", "Player changed dimension since preview");
        }
        ServerLevel level = player.serverLevel();
        for (Write write : preview.writes) {
            if (!allowedRegion.contains(write.pos)) return error("outside_allowed_region", "Build region changed");
            if (!loaded(level, write.pos)) return error("chunk_unloaded", "Target chunk unloaded after preview");
            if (!level.getBlockState(write.pos).equals(write.before) || level.getBlockEntity(write.pos) != null) {
                return error("state_changed", "A target block changed after preview; preview again");
            }
        }
        if (!fingerprintCurrent(preview.dimension, level, preview.writes).equals(preview.preFingerprint)) {
            return error("state_changed", "Pre-state fingerprint no longer matches");
        }

        EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(NeoForgeAdapter.adapt(level)).actor(NeoForgeAdapter.adaptPlayer(player))
                .maxBlocks(MAX_WRITES).build();
        edit.setReorderMode(EditSession.ReorderMode.NONE);
        edit.setTrackingHistory(true);
        long started = System.nanoTime();
        String failure = null;
        try {
            for (Write write : preview.writes) {
                if (!write.before.equals(write.desired)) {
                    edit.setBlock(BlockVector3.at(write.pos.getX(), write.pos.getY(), write.pos.getZ()),
                            NeoForgeAdapter.adapt(write.desired));
                }
            }
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        } finally {
            try {
                edit.close();
            } catch (Exception e) {
                failure = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            }
        }

        Map<BlockPos, BlockState> actualAfter = new HashMap<>();
        int changed = 0;
        boolean matchesPlan = true;
        for (Write write : preview.writes) {
            BlockState actual = level.getBlockState(write.pos);
            actualAfter.put(write.pos, actual);
            if (!actual.equals(write.before)) changed++;
            if (!actual.equals(write.desired)) matchesPlan = false;
        }
        boolean completed = failure == null && matchesPlan;
        String operationId = null;
        if (changed > 0) {
            operationId = UUID.randomUUID().toString();
            pendingUndo = new Operation(operationId, preview, edit, Map.copyOf(actualAfter), changed, completed);
        }
        JsonObject result = success(completed ? "completed" : "failed", completed);
        result.addProperty("ok", completed);
        result.addProperty("accepted", true);
        result.addProperty("preview_id", previewId);
        result.add("affected_bounds", preview.bounds.toJson());
        result.addProperty("changed_blocks", changed);
        result.addProperty("worldedit_change_set_size", edit.getChangeSet().size());
        result.addProperty("operation_id", operationId);
        result.addProperty("undo_available", operationId != null);
        result.addProperty("post_state_fingerprint", fingerprintStates(preview.dimension, preview.writes, actualAfter));
        result.addProperty("duration_ms", (System.nanoTime() - started) / 1_000_000.0);
        if (!completed) {
            result.addProperty("error_code", "execution_failed");
            result.addProperty("error", failure != null ? failure : "World state did not match the planned blocks");
        }
        return result;
    }

    JsonObject undo(ServerPlayer player, JsonObject request) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        String id = stringField(request, "operation_id");
        if (id == null || id.length() > 64) return error("invalid_request", "Invalid operation_id");
        Operation operation = pendingUndo;
        if (operation == null) return error("empty_undo_history", "No bounded bridge operation is pending undo");
        if (!operation.id.equals(id)) return error("operation_mismatch", "Operation ID does not match pending undo");
        if (!operation.preview.username.equals(player.getGameProfile().getName())) {
            return error("player_mismatch", "Operation belongs to another player");
        }
        if (!operation.preview.dimension.equals(dimension(player))) {
            return error("dimension_changed", "Player is no longer in the operation dimension");
        }
        ServerLevel level = player.serverLevel();
        for (Write write : operation.preview.writes) {
            if (!loaded(level, write.pos)) return error("chunk_unloaded", "Target chunk unloaded; undo will not generate it");
            if (!level.getBlockState(write.pos).equals(operation.actualAfter.get(write.pos))
                    || level.getBlockEntity(write.pos) != null) {
                return error("undo_conflict", "World changed after this operation; undo would overwrite later edits");
            }
        }

        EditSession reversal = WorldEdit.getInstance().newEditSessionBuilder()
                .world(NeoForgeAdapter.adapt(level)).actor(NeoForgeAdapter.adaptPlayer(player))
                .maxBlocks(MAX_WRITES).build();
        reversal.setReorderMode(EditSession.ReorderMode.NONE);
        String failure = null;
        boolean usedWorldEditHistory = operation.edit.getChangeSet().size() > 0;
        try {
            if (usedWorldEditHistory) {
                operation.edit.undo(reversal);
            } else {
                // A direct snapshot reversal remains possible if WorldEdit recorded no history.
                for (Write write : operation.preview.writes) {
                    if (!write.before.equals(operation.actualAfter.get(write.pos))) {
                        reversal.setBlock(BlockVector3.at(write.pos.getX(), write.pos.getY(), write.pos.getZ()),
                                NeoForgeAdapter.adapt(write.before));
                    }
                }
            }
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        } finally {
            try {
                reversal.close();
            } catch (Exception e) {
                failure = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            }
        }
        boolean restored = true;
        for (Write write : operation.preview.writes) {
            if (!level.getBlockState(write.pos).equals(write.before)) restored = false;
        }
        boolean completed = failure == null && restored;
        if (completed) pendingUndo = null;
        JsonObject result = success(completed ? "undone" : "failed", completed);
        result.addProperty("ok", completed);
        result.addProperty("undone_operation_id", id);
        result.add("affected_bounds", operation.preview.bounds.toJson());
        result.addProperty("restored_blocks", operation.changedBlocks);
        result.addProperty("restored_to_pre_state", restored);
        result.addProperty("more_bridge_undo_history", false);
        result.addProperty("more_player_undo_history", "unknown");
        result.addProperty("used_worldedit_history", usedWorldEditHistory);
        result.addProperty("restored_state_fingerprint", fingerprintCurrent(operation.preview.dimension,
                level, operation.preview.writes));
        result.addProperty("expected_pre_state_fingerprint", operation.preview.preFingerprint);
        if (!completed) {
            result.addProperty("error_code", "undo_failed");
            result.addProperty("error", failure != null ? failure : "WorldEdit undo did not restore original states");
        }
        return result;
    }

    JsonObject pending(ServerPlayer player) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        Operation operation = pendingUndo;
        if (operation == null) {
            JsonObject empty = success("empty", true);
            empty.addProperty("operation_id", (String) null);
            empty.addProperty("more_bridge_undo_history", false);
            return empty;
        }
        if (!operation.preview.username.equals(player.getGameProfile().getName())) {
            return error("player_mismatch", "Pending operation belongs to another player");
        }
        ServerLevel level = player.serverLevel();
        boolean chunksLoaded = operation.preview.dimension.equals(dimension(player));
        boolean matchesPostState = chunksLoaded;
        for (Write write : operation.preview.writes) {
            if (!chunksLoaded || !loaded(level, write.pos)) {
                chunksLoaded = false;
                matchesPostState = false;
                break;
            }
            if (!level.getBlockState(write.pos).equals(operation.actualAfter.get(write.pos))) {
                matchesPostState = false;
            }
        }
        JsonObject result = success("undo_pending", true);
        result.addProperty("operation_id", operation.id);
        result.addProperty("operation_completed", operation.appliedSuccessfully);
        result.addProperty("operation_dimension", operation.preview.dimension);
        result.add("affected_bounds", operation.preview.bounds.toJson());
        result.addProperty("changed_blocks", operation.changedBlocks);
        result.addProperty("post_state_fingerprint", fingerprintStates(operation.preview.dimension,
                operation.preview.writes, operation.actualAfter));
        result.addProperty("chunks_loaded", chunksLoaded);
        result.addProperty("current_matches_post_state", matchesPostState);
        result.addProperty("undo_available", chunksLoaded && matchesPostState);
        result.addProperty("more_bridge_undo_history", false);
        return result;
    }

    JsonObject validateSelection(ServerPlayer player, BlockPos min, BlockPos max) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            return error("invalid_bounds", "min must be at or below max on every axis");
        }
        ServerLevel level = player.serverLevel();
        if (!allowedRegion.contains(min) || !allowedRegion.contains(max)
                || min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight()) {
            return error("outside_allowed_region", "Selection is outside configured region or world height");
        }
        Bounds bounds = new Bounds(min, max);
        if (bounds.volume() > MAX_VOLUME) return error("size_limit", "Selection volume exceeds 64 blocks");
        for (long x = min.getX(); x <= max.getX(); x++) {
            for (long z = min.getZ(); z <= max.getZ(); z++) {
                if (level.getChunkSource().getChunkNow(((int) x) >> 4, ((int) z) >> 4) == null) {
                    return error("chunk_unloaded", "Selection chunk is not loaded");
                }
            }
        }
        return null;
    }

    private JsonObject checkEnabled(ServerPlayer player) {
        if (allowedRegion == null) return error("build_disabled", "No WEDIT_BRIDGE_ALLOWED_REGION is configured");
        if (!player.hasPermissions(2)) return error("permission_denied", "Player is not a Minecraft operator");
        if (!allowedRegion.dimension.equals(dimension(player))) {
            return error("dimension_mismatch", "Player is not in the configured build dimension");
        }
        return null;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static String dimension(ServerPlayer player) {
        return player.serverLevel().dimension().location().toString();
    }

    private static BuildRegion parseAllowedRegion(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String[] fields = raw.split(";", -1);
            if (fields.length != 3 || ResourceLocation.tryParse(fields[0]) == null) throw new IllegalArgumentException();
            BlockPos min = parsePoint(fields[1]);
            BlockPos max = parsePoint(fields[2]);
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
                throw new IllegalArgumentException();
            }
            return new BuildRegion(fields[0], min, max);
        } catch (IllegalArgumentException e) {
            WeditMcpBridge.LOGGER.error("Invalid WEDIT_BRIDGE_ALLOWED_REGION; bounded writes disabled");
            return null;
        }
    }

    private static BlockPos parsePoint(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException();
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private void purgeExpired() {
        long now = System.nanoTime();
        previews.values().removeIf(preview -> now - preview.expiresAtNanos >= 0);
    }

    private static String fingerprint(String dimension, List<Write> writes, boolean desired) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (Write write : writes) states.put(write.pos, desired ? write.desired : write.before);
        return fingerprintStates(dimension, writes, states);
    }

    private static String fingerprintCurrent(String dimension, ServerLevel level, List<Write> writes) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (Write write : writes) states.put(write.pos, level.getBlockState(write.pos));
        return fingerprintStates(dimension, writes, states);
    }

    private static String fingerprintStates(String dimension, List<Write> writes,
                                            Map<BlockPos, BlockState> states) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((dimension + "\n").getBytes(StandardCharsets.UTF_8));
            for (Write write : writes) {
                String line = write.pos.getX() + "," + write.pos.getY() + "," + write.pos.getZ()
                        + "=" + stateId(states.get(write.pos)) + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stateId(BlockState state) {
        return NeoForgeAdapter.adapt(state).getAsString();
    }

    private static JsonObject counts(Map<String, Integer> values) {
        JsonObject result = new JsonObject();
        values.forEach((key, count) -> result.addProperty(key, count));
        return result;
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray result = new JsonArray();
        values.stream().sorted().forEach(result::add);
        return result;
    }

    private static Integer integerField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber() || !primitive.getAsString().matches("-?(0|[1-9][0-9]*)")) return null;
        try {
            return Integer.parseInt(primitive.getAsString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
        return element.getAsString();
    }

    private static JsonObject point(BlockPos pos) {
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        return result;
    }

    private static JsonObject success(String status, boolean completed) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("status", status);
        result.addProperty("completed", completed);
        return result;
    }

    private static JsonObject error(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error_code", code);
        result.addProperty("error", message);
        return result;
    }

    private record BuildRegion(String dimension, BlockPos min, BlockPos max) {
        boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    private record Write(BlockPos pos, BlockState desired, BlockState before) { }

    private record Preview(String id, String username, String dimension, long expiresAtNanos,
                           List<Write> writes, Bounds bounds, String preFingerprint) { }

    private record Operation(String id, Preview preview, EditSession edit,
                             Map<BlockPos, BlockState> actualAfter, int changedBlocks,
                             boolean appliedSuccessfully) { }

    private record Bounds(BlockPos min, BlockPos max) {
        Bounds(BlockPos pos) { this(pos, pos); }

        Bounds include(BlockPos pos) {
            return new Bounds(new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()),
                    Math.min(min.getZ(), pos.getZ())),
                    new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()),
                            Math.max(max.getZ(), pos.getZ())));
        }

        long volume() {
            long width = (long) max.getX() - min.getX() + 1;
            long height = (long) max.getY() - min.getY() + 1;
            long length = (long) max.getZ() - min.getZ() + 1;
            if (width > MAX_VOLUME || height > MAX_VOLUME || length > MAX_VOLUME) return MAX_VOLUME + 1L;
            return width * height * length;
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.add("min", point(min));
            result.add("max", point(max));
            result.addProperty("width", (long) max.getX() - min.getX() + 1);
            result.addProperty("height", (long) max.getY() - min.getY() + 1);
            result.addProperty("length", (long) max.getZ() - min.getZ() + 1);
            result.addProperty("block_volume", volume());
            return result;
        }
    }
}
