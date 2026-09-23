package com.aeronauticsmcp.weditmcpbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    private static final int MAX_EXPLICIT_WRITES = 64;
    private static final int MAX_WRITES = 512;
    private static final int MAX_VOLUME = 512;
    private static final int MAX_PREVIEWS = 8;
    private static final int MAX_HISTORY = 16;
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
    private final Path journalPath;
    private final String worldIdentity;
    // This object is only accessed from Minecraft's server thread.
    private final Map<String, Preview> previews = new HashMap<>();
    // One outstanding operation prevents history eviction and makes targeted undo unambiguous.
    private Operation pendingUndo;
    private JsonObject pendingRecord;
    private final List<JsonObject> recentOperations = new ArrayList<>();
    private boolean journalHealthy = true;

    BoundedBuildService(MinecraftServer server) {
        Path worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        worldIdentity = worldPath.toString();
        journalPath = worldPath.resolve("data")
                .resolve("weditmcpbridge-operations.json");
        allowedRegion = parseAllowedRegion(System.getenv("WEDIT_BRIDGE_ALLOWED_REGION"));
        loadJournal();
        if (allowedRegion == null) {
            WeditMcpBridge.LOGGER.info("Bounded WorldEdit writes disabled; WEDIT_BRIDGE_ALLOWED_REGION not configured");
        } else {
            WeditMcpBridge.LOGGER.warn("Bounded WorldEdit writes enabled for {} from {} to {}",
                    allowedRegion.dimension, allowedRegion.min, allowedRegion.max);
        }
    }

    JsonObject preview(ServerPlayer player, JsonObject request) {
        return previewExplicit(player, request);
    }

    JsonObject previewFillCuboid(ServerPlayer player, JsonObject request) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        BlockPos min = pointField(request, "min");
        BlockPos max = pointField(request, "max");
        String block = stringField(request, "block");
        if (min == null || max == null || block == null) {
            return error("invalid_request", "min, max, and block are required");
        }
        JsonObject invalid = validateSelection(player, min, max);
        if (invalid != null) return invalid;
        JsonArray blocks = new JsonArray();
        for (long x = min.getX(); x <= max.getX(); x++) {
            for (long y = min.getY(); y <= max.getY(); y++) {
                for (long z = min.getZ(); z <= max.getZ(); z++) {
                    JsonObject item = point(new BlockPos((int) x, (int) y, (int) z));
                    item.addProperty("block", block);
                    blocks.add(item);
                }
            }
        }
        return previewWrites(player, blocks, "fill_cuboid", MAX_WRITES);
    }

    private JsonObject previewExplicit(ServerPlayer player, JsonObject request) {
        JsonElement blocksElement = request.get("blocks");
        if (blocksElement == null || !blocksElement.isJsonArray()) {
            return error("invalid_request", "blocks must be an array of explicit block writes");
        }
        return previewWrites(player, blocksElement.getAsJsonArray(), "set_blocks", MAX_EXPLICIT_WRITES);
    }

    private JsonObject previewWrites(ServerPlayer player, JsonArray blocks, String operationType, int writeLimit) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        if (!journalHealthy) return error("journal_unavailable", "Operation journal is unavailable; writes are disabled");
        if (pendingUndo != null) return error("undo_pending", "Undo the previous operation before another preview");
        if (blocks.size() == 0 || blocks.size() > writeLimit) {
            return error("size_limit", "Block writes exceed operation limit of " + writeLimit);
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
            if (!before.equals(desired)) {
                expectedChanged++;
                overwrittenPalette.merge(stateId(before), 1, Integer::sum);
            }
            targetPalette.add(id);
            writes.add(new Write(pos, desired, before));
            bounds = bounds == null ? new Bounds(pos) : bounds.include(pos);
        }
        if (bounds == null || bounds.volume() > MAX_VOLUME) {
            return error("size_limit", "Write bounding volume exceeds " + MAX_VOLUME + " blocks");
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
                System.nanoTime() + PREVIEW_TTL_NANOS, List.copyOf(writes), bounds, fingerprint, operationType));
        JsonObject result = success("previewed", false);
        result.addProperty("preview_id", id);
        result.addProperty("expires_at", Instant.now().toEpochMilli() + 60_000);
        result.addProperty("operation_type", operationType);
        result.add("bounds", bounds.toJson());
        result.addProperty("inspected_blocks", writes.size());
        result.addProperty("expected_changed_blocks", expectedChanged);
        result.addProperty("max_changed_blocks", writeLimit);
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
        if (!journalHealthy) return error("journal_unavailable", "Operation journal is unavailable; writes are disabled");
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

        // Persist the exact plan before the first world mutation. A restart can then
        // recover a fully or partly applied operation without guessing from chat/logs.
        String operationId = UUID.randomUUID().toString();
        pendingRecord = preparedRecord(operationId, preview);
        pendingUndo = new Operation(operationId, preview, null, beforeStates(preview.writes), 0, false);
        if (!saveJournal()) {
            pendingRecord = null;
            pendingUndo = null;
            return error("journal_unavailable", "Could not persist the operation plan; no blocks were changed");
        }

        long started = System.nanoTime();
        EditSession edit = null;
        String failure = null;
        try {
            edit = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(NeoForgeAdapter.adapt(level)).actor(NeoForgeAdapter.adaptPlayer(player))
                    .maxBlocks(MAX_WRITES).build();
            edit.setReorderMode(EditSession.ReorderMode.NONE);
            edit.setTrackingHistory(true);
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
                if (edit != null) edit.close();
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
        pendingUndo = new Operation(operationId, preview, edit, Map.copyOf(actualAfter), changed, completed);
        pendingRecord.addProperty("status", completed ? "completed" : "failed");
        pendingRecord.addProperty("completed", completed);
        pendingRecord.addProperty("apply_completed", completed);
        pendingRecord.addProperty("changed_blocks", changed);
        pendingRecord.addProperty("undo_available", changed > 0);
        pendingRecord.addProperty("post_state_fingerprint", fingerprintStates(preview.dimension, preview.writes, actualAfter));
        pendingRecord.addProperty("updated_at", Instant.now().toEpochMilli());
        pendingRecord.addProperty("worldedit_change_set_size", edit == null ? 0 : edit.getChangeSet().size());
        if (failure != null) pendingRecord.addProperty("error", failure);
        recordAfterStates(pendingRecord, preview.writes, actualAfter);
        if (!saveJournal()) {
            JsonObject unknown = error("completion_unknown", "WorldEdit ran but the result could not be saved to the operation journal");
            unknown.addProperty("operation_id", operationId);
            unknown.addProperty("changed_blocks", changed);
            unknown.addProperty("undo_available", changed > 0);
            unknown.add("affected_bounds", preview.bounds.toJson());
            return unknown;
        }
        if (changed == 0 && !archivePending("failed", false)) {
            JsonObject unknown = error("completion_unknown", "No target changed, but the operation journal could not be finalized");
            unknown.addProperty("operation_id", operationId);
            return unknown;
        }
        JsonObject result = success(completed ? "completed" : "failed", completed);
        result.addProperty("ok", completed);
        result.addProperty("accepted", true);
        result.addProperty("preview_id", previewId);
        result.add("affected_bounds", preview.bounds.toJson());
        result.addProperty("changed_blocks", changed);
        result.addProperty("worldedit_change_set_size", edit == null ? 0 : edit.getChangeSet().size());
        result.addProperty("operation_id", operationId);
        result.addProperty("undo_available", changed > 0);
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
        if (pendingRecord != null && "prepared".equals(stringField(pendingRecord, "status"))) {
            JsonObject recoveryError = recoverPrepared(player);
            if (recoveryError != null) return recoveryError;
        }
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
        boolean allAtPostState = true;
        int blocksToRestore = 0;
        for (Write write : operation.preview.writes) {
            if (!allowedRegion.contains(write.pos)) {
                return error("outside_allowed_region", "Operation is outside the currently configured region");
            }
            if (!loaded(level, write.pos)) return error("chunk_unloaded", "Target chunk unloaded; undo will not generate it");
            BlockState current = level.getBlockState(write.pos);
            if (level.getBlockEntity(write.pos) != null
                    || (!current.equals(operation.actualAfter.get(write.pos)) && !current.equals(write.before))) {
                return error("undo_conflict", "World changed after this operation; undo would overwrite later edits");
            }
            if (!current.equals(operation.actualAfter.get(write.pos))) allAtPostState = false;
            if (!current.equals(write.before)) blocksToRestore++;
        }

        EditSession reversal = WorldEdit.getInstance().newEditSessionBuilder()
                .world(NeoForgeAdapter.adapt(level)).actor(NeoForgeAdapter.adaptPlayer(player))
                .maxBlocks(MAX_WRITES).build();
        reversal.setReorderMode(EditSession.ReorderMode.NONE);
        String failure = null;
        boolean usedWorldEditHistory = allAtPostState && operation.edit != null
                && operation.edit.getChangeSet().size() > 0;
        try {
            if (usedWorldEditHistory) {
                operation.edit.undo(reversal);
            } else {
                // A direct snapshot reversal remains possible if WorldEdit recorded no history.
                for (Write write : operation.preview.writes) {
                    if (!write.before.equals(level.getBlockState(write.pos))) {
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
        if (completed && pendingRecord != null) {
            pendingRecord.addProperty("restored_blocks", blocksToRestore);
            pendingRecord.addProperty("restored_state_fingerprint", fingerprintCurrent(
                    operation.preview.dimension, level, operation.preview.writes));
            pendingRecord.addProperty("used_worldedit_history", usedWorldEditHistory);
        }
        if (completed && !archivePending("undone", operation.appliedSuccessfully)) {
            JsonObject unknown = error("completion_unknown", "Blocks were restored but operation journal update failed");
            unknown.addProperty("undone_operation_id", id);
            unknown.addProperty("restored_to_pre_state", true);
            unknown.add("affected_bounds", operation.preview.bounds.toJson());
            return unknown;
        }
        JsonObject result = success(completed ? "undone" : "failed", completed);
        result.addProperty("ok", completed);
        result.addProperty("undone_operation_id", id);
        result.add("affected_bounds", operation.preview.bounds.toJson());
        result.addProperty("restored_blocks", blocksToRestore);
        result.addProperty("restored_to_pre_state", restored);
        result.addProperty("more_bridge_undo_history", false);
        result.addProperty("more_player_undo_history", "unknown");
        result.addProperty("used_worldedit_history", usedWorldEditHistory);
        result.addProperty("recovered_from_journal", operation.edit == null);
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
        if (!journalHealthy && pendingUndo == null) {
            return error("journal_unavailable", "Operation journal could not be loaded");
        }
        if (pendingRecord != null && "prepared".equals(stringField(pendingRecord, "status"))) {
            JsonObject recoveryError = recoverPrepared(player);
            if (recoveryError != null) return recoveryError;
        }
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
        boolean safelyUndoable = chunksLoaded;
        for (Write write : operation.preview.writes) {
            if (!chunksLoaded || !loaded(level, write.pos)) {
                chunksLoaded = false;
                matchesPostState = false;
                safelyUndoable = false;
                break;
            }
            BlockState current = level.getBlockState(write.pos);
            if (level.getBlockEntity(write.pos) != null
                    || (!current.equals(operation.actualAfter.get(write.pos)) && !current.equals(write.before))) {
                safelyUndoable = false;
                matchesPostState = false;
            }
            if (!current.equals(operation.actualAfter.get(write.pos))) {
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
        result.addProperty("undo_available", safelyUndoable);
        result.addProperty("more_bridge_undo_history", false);
        result.addProperty("recovered_from_journal", operation.edit == null);
        return result;
    }

    JsonObject operationStatus(ServerPlayer player, JsonObject request) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        if (!journalHealthy && pendingUndo == null) {
            return error("journal_unavailable", "Operation journal could not be loaded");
        }
        String id = stringField(request, "operation_id");
        if (id == null || id.length() > 64) return error("invalid_request", "Invalid operation_id");
        JsonObject livePending = null;
        if (pendingRecord != null && id.equals(stringField(pendingRecord, "operation_id"))) {
            livePending = pending(player);
            if (!livePending.get("ok").getAsBoolean()) return livePending;
        }
        JsonObject record = findOperation(id);
        if (record == null || !player.getGameProfile().getName().equals(stringField(record, "username"))) {
            return error("operation_unavailable", "No retained operation with that ID for this player");
        }
        JsonObject result = success("found", true);
        JsonObject publicOperation = publicRecord(record);
        if (livePending != null && pendingRecord == record) {
            publicOperation.addProperty("undo_available", livePending.get("undo_available").getAsBoolean());
            publicOperation.addProperty("chunks_loaded", livePending.get("chunks_loaded").getAsBoolean());
            publicOperation.addProperty("current_matches_post_state",
                    livePending.get("current_matches_post_state").getAsBoolean());
            publicOperation.addProperty("recovered_from_journal",
                    livePending.get("recovered_from_journal").getAsBoolean());
        }
        result.add("operation", publicOperation);
        return result;
    }

    JsonObject listOperations(ServerPlayer player) {
        JsonObject disabled = checkEnabled(player);
        if (disabled != null) return disabled;
        if (!journalHealthy && pendingUndo == null) {
            return error("journal_unavailable", "Operation journal could not be loaded");
        }
        JsonObject livePending = null;
        if (pendingRecord != null) {
            livePending = pending(player);
            if (!livePending.get("ok").getAsBoolean()) return livePending;
        }
        JsonArray records = new JsonArray();
        String username = player.getGameProfile().getName();
        if (pendingRecord != null && username.equals(stringField(pendingRecord, "username"))) {
            JsonObject current = publicRecord(pendingRecord);
            current.addProperty("undo_available", livePending.get("undo_available").getAsBoolean());
            current.addProperty("chunks_loaded", livePending.get("chunks_loaded").getAsBoolean());
            current.addProperty("current_matches_post_state",
                    livePending.get("current_matches_post_state").getAsBoolean());
            current.addProperty("recovered_from_journal",
                    livePending.get("recovered_from_journal").getAsBoolean());
            records.add(current);
        }
        for (int i = recentOperations.size() - 1; i >= 0; i--) {
            JsonObject record = recentOperations.get(i);
            if (username.equals(stringField(record, "username"))) records.add(publicRecord(record));
        }
        JsonObject result = success("listed", true);
        result.add("operations", records);
        result.addProperty("retained_count", records.size());
        result.addProperty("history_limit", MAX_HISTORY);
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
        if (bounds.volume() > MAX_VOLUME) return error("size_limit", "Selection volume exceeds " + MAX_VOLUME + " blocks");
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

    private static BlockPos pointField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) return null;
        JsonObject point = element.getAsJsonObject();
        Integer x = integerField(point, "x");
        Integer y = integerField(point, "y");
        Integer z = integerField(point, "z");
        return x == null || y == null || z == null ? null : new BlockPos(x, y, z);
    }

    private static Map<BlockPos, BlockState> beforeStates(List<Write> writes) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (Write write : writes) states.put(write.pos, write.before);
        return Map.copyOf(states);
    }

    private static JsonObject preparedRecord(String id, Preview preview) {
        JsonObject record = new JsonObject();
        record.addProperty("operation_id", id);
        record.addProperty("preview_id", preview.id);
        record.addProperty("username", preview.username);
        record.addProperty("dimension", preview.dimension);
        record.addProperty("operation_type", preview.operationType);
        record.addProperty("status", "prepared");
        record.addProperty("accepted", true);
        record.addProperty("completed", false);
        record.addProperty("apply_completed", false);
        record.addProperty("undo_completed", false);
        record.addProperty("undo_available", false);
        record.addProperty("changed_blocks", 0);
        record.add("affected_bounds", preview.bounds.toJson());
        record.addProperty("pre_state_fingerprint", preview.preFingerprint);
        record.addProperty("created_at", Instant.now().toEpochMilli());
        record.addProperty("updated_at", Instant.now().toEpochMilli());
        JsonArray writes = new JsonArray();
        for (Write write : preview.writes) {
            JsonObject item = point(write.pos);
            item.addProperty("before", stateId(write.before));
            item.addProperty("desired", stateId(write.desired));
            writes.add(item);
        }
        record.add("writes", writes);
        return record;
    }

    private static void recordAfterStates(JsonObject record, List<Write> writes,
                                          Map<BlockPos, BlockState> actualAfter) {
        JsonArray savedWrites = record.getAsJsonArray("writes");
        for (int i = 0; i < writes.size(); i++) {
            savedWrites.get(i).getAsJsonObject().addProperty("after", stateId(actualAfter.get(writes.get(i).pos)));
        }
    }

    private JsonObject recoverPrepared(ServerPlayer player) {
        Operation operation = pendingUndo;
        if (operation == null || pendingRecord == null) {
            return error("journal_unavailable", "Prepared operation could not be recovered");
        }
        if (!operation.preview.username.equals(player.getGameProfile().getName())) {
            return error("player_mismatch", "Prepared operation belongs to another player");
        }
        if (!operation.preview.dimension.equals(dimension(player))) {
            return error("dimension_changed", "Player is not in the prepared operation dimension");
        }
        ServerLevel level = player.serverLevel();
        Map<BlockPos, BlockState> after = new HashMap<>();
        int changed = 0;
        for (Write write : operation.preview.writes) {
            if (!allowedRegion.contains(write.pos)) {
                return error("outside_allowed_region", "Prepared operation is outside the current allowed region");
            }
            if (!loaded(level, write.pos)) return error("chunk_unloaded", "Prepared operation chunk is unloaded");
            if (level.getBlockEntity(write.pos) != null) {
                return error("recovery_conflict", "A prepared operation target now has a block entity");
            }
            BlockState current = level.getBlockState(write.pos);
            if (!current.equals(write.before) && !current.equals(write.desired)) {
                return error("recovery_conflict", "Prepared operation target differs from both planned states");
            }
            if (!current.equals(write.before)) changed++;
            after.put(write.pos, current);
        }
        if (changed == 0) {
            if (!archivePending("not_applied", false)) {
                return error("journal_unavailable", "Could not record that the prepared operation made no changes");
            }
            return null;
        }
        String status = changed == operation.preview.writes.stream()
                .filter(write -> !write.before.equals(write.desired)).count() ? "completed_recovered" : "partial_recovered";
        pendingUndo = new Operation(operation.id, operation.preview, null, Map.copyOf(after), changed,
                "completed_recovered".equals(status));
        pendingRecord.addProperty("status", status);
        pendingRecord.addProperty("completed", "completed_recovered".equals(status));
        pendingRecord.addProperty("apply_completed", "completed_recovered".equals(status));
        pendingRecord.addProperty("changed_blocks", changed);
        pendingRecord.addProperty("undo_available", true);
        pendingRecord.addProperty("post_state_fingerprint", fingerprintStates(operation.preview.dimension,
                operation.preview.writes, after));
        pendingRecord.addProperty("updated_at", Instant.now().toEpochMilli());
        recordAfterStates(pendingRecord, operation.preview.writes, after);
        if (!saveJournal()) {
            return error("journal_unavailable", "Could not persist recovered operation state");
        }
        return null;
    }

    private boolean archivePending(String status, boolean completed) {
        if (pendingRecord == null) return false;
        JsonObject oldRecord = pendingRecord;
        Operation oldOperation = pendingUndo;
        JsonObject archived = publicRecord(oldRecord);
        archived.addProperty("status", status);
        archived.addProperty("completed", completed);
        archived.addProperty("undo_completed", "undone".equals(status) || "undone_recovered".equals(status));
        archived.addProperty("undo_available", false);
        archived.addProperty("updated_at", Instant.now().toEpochMilli());
        recentOperations.add(archived);
        JsonObject evicted = null;
        if (recentOperations.size() > MAX_HISTORY) evicted = recentOperations.remove(0);
        pendingRecord = null;
        pendingUndo = null;
        if (saveJournal()) return true;
        if (evicted != null) recentOperations.add(0, evicted);
        recentOperations.remove(archived);
        pendingRecord = oldRecord;
        pendingUndo = oldOperation;
        return false;
    }

    private JsonObject findOperation(String id) {
        if (pendingRecord != null && id.equals(stringField(pendingRecord, "operation_id"))) return pendingRecord;
        for (JsonObject record : recentOperations) {
            if (id.equals(stringField(record, "operation_id"))) return record;
        }
        return null;
    }

    private static JsonObject publicRecord(JsonObject record) {
        JsonObject copy = JsonParser.parseString(record.toString()).getAsJsonObject();
        copy.remove("writes");
        return copy;
    }

    private boolean saveJournal() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 1);
        root.addProperty("world_save_path", worldIdentity);
        if (pendingRecord != null) root.add("pending", pendingRecord);
        else root.add("pending", com.google.gson.JsonNull.INSTANCE);
        JsonArray recent = new JsonArray();
        for (JsonObject record : recentOperations) recent.add(record);
        root.add("recent", recent);
        Path temporary = journalPath.resolveSibling(journalPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(journalPath.getParent());
            byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > 262_144) throw new IOException("Operation journal exceeds 256 KiB");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, journalPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, journalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            journalHealthy = false;
            WeditMcpBridge.LOGGER.error("WorldEdit operation journal unavailable at {}: {}",
                    journalPath, e.getMessage());
            return false;
        }
    }

    private void loadJournal() {
        if (!Files.exists(journalPath)) return;
        try {
            if (Files.size(journalPath) > 262_144) throw new IOException("Journal exceeds 256 KiB");
            JsonObject root = JsonParser.parseString(Files.readString(journalPath, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (integerField(root, "format_version") == null || integerField(root, "format_version") != 1) {
                throw new IOException("Unsupported journal format");
            }
            if (!worldIdentity.equals(stringField(root, "world_save_path"))) {
                throw new IOException("Journal belongs to a different world save path");
            }
            JsonArray recent = root.getAsJsonArray("recent");
            if (recent == null || recent.size() > MAX_HISTORY) throw new IOException("Invalid retained history");
            for (JsonElement item : recent) {
                JsonObject record = item.getAsJsonObject();
                if (stringField(record, "operation_id") == null || stringField(record, "username") == null
                        || record.has("writes")) throw new IOException("Malformed history entry");
                recentOperations.add(record);
            }
            JsonElement pending = root.get("pending");
            if (pending != null && pending.isJsonObject()) {
                pendingRecord = pending.getAsJsonObject();
                pendingUndo = restorePending(pendingRecord);
            }
        } catch (Exception e) {
            journalHealthy = false;
            recentOperations.clear();
            pendingRecord = null;
            pendingUndo = null;
            WeditMcpBridge.LOGGER.error("WorldEdit operation journal is invalid at {}; writes disabled: {}",
                    journalPath, e.getMessage());
        }
    }

    private static Operation restorePending(JsonObject record) throws IOException {
        String id = stringField(record, "operation_id");
        String username = stringField(record, "username");
        String dimension = stringField(record, "dimension");
        String status = stringField(record, "status");
        String preFingerprint = stringField(record, "pre_state_fingerprint");
        String postFingerprint = stringField(record, "post_state_fingerprint");
        String operationType = stringField(record, "operation_type");
        if (id == null || username == null || dimension == null || preFingerprint == null || status == null
                || ResourceLocation.tryParse(dimension) == null || !username.matches("[A-Za-z0-9_]{1,16}")
                || !("set_blocks".equals(operationType) || "fill_cuboid".equals(operationType))
                || !("prepared".equals(status) || "completed".equals(status) || "failed".equals(status)
                    || "completed_recovered".equals(status) || "partial_recovered".equals(status))) {
            throw new IOException("Invalid pending metadata");
        }
        try { UUID.fromString(id); } catch (IllegalArgumentException e) { throw new IOException("Invalid operation ID", e); }
        JsonArray storedWrites = record.getAsJsonArray("writes");
        if (storedWrites == null || storedWrites.isEmpty() || storedWrites.size() > MAX_WRITES) {
            throw new IOException("Invalid pending write count");
        }
        List<Write> writes = new ArrayList<>();
        Map<BlockPos, BlockState> actualAfter = new HashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        Bounds bounds = null;
        for (JsonElement item : storedWrites) {
            JsonObject stored = item.getAsJsonObject();
            BlockPos pos = pointField(stored, "position");
            if (pos == null) {
                Integer x = integerField(stored, "x");
                Integer y = integerField(stored, "y");
                Integer z = integerField(stored, "z");
                if (x == null || y == null || z == null) throw new IOException("Invalid pending coordinates");
                pos = new BlockPos(x, y, z);
            }
            if (!seen.add(pos)) throw new IOException("Duplicate pending position");
            BlockState before = savedState(stored, "before");
            BlockState desired = savedState(stored, "desired");
            if (!SAFE_BLOCKS.contains(stateId(desired)) || (!before.isAir() && !before.equals(desired))) {
                throw new IOException("Pending states exceed safe palette");
            }
            writes.add(new Write(pos, desired, before));
            if (stored.has("after")) actualAfter.put(pos, savedState(stored, "after"));
            bounds = bounds == null ? new Bounds(pos) : bounds.include(pos);
        }
        writes.sort(POSITION_ORDER);
        if (bounds == null || bounds.volume() > MAX_VOLUME || !fingerprint(dimension, writes, false).equals(preFingerprint)) {
            throw new IOException("Pending bounds or fingerprint mismatch");
        }
        JsonObject storedBounds = record.getAsJsonObject("affected_bounds");
        if (storedBounds == null || !bounds.min.equals(pointField(storedBounds, "min"))
                || !bounds.max.equals(pointField(storedBounds, "max"))) {
            throw new IOException("Pending affected bounds mismatch");
        }
        if ("prepared".equals(status) && !actualAfter.isEmpty()) {
            throw new IOException("Prepared operation unexpectedly contains post-state");
        }
        if (!"prepared".equals(status) && actualAfter.size() != writes.size()) {
            throw new IOException("Pending post-state is incomplete");
        }
        if (!"prepared".equals(status)
                && (postFingerprint == null || !postFingerprint.equals(fingerprintStates(dimension, writes, actualAfter)))) {
            throw new IOException("Pending post-state fingerprint mismatch");
        }
        Preview preview = new Preview(stringField(record, "preview_id"), username, dimension, 0L,
                List.copyOf(writes), bounds, preFingerprint, operationType);
        int changed = 0;
        for (Write write : writes) {
            BlockState after = actualAfter.get(write.pos);
            if (after != null && !after.equals(write.before)) changed++;
            if (after != null && !after.equals(write.before) && !after.equals(write.desired)) {
                throw new IOException("Pending target changed to an unplanned state");
            }
        }
        if (!"prepared".equals(status) && !Integer.valueOf(changed).equals(integerField(record, "changed_blocks"))) {
            throw new IOException("Pending changed count mismatch");
        }
        return new Operation(id, preview, null, Map.copyOf(actualAfter), changed,
                Boolean.TRUE.equals(record.has("completed") && record.get("completed").getAsBoolean()));
    }

    private static BlockState savedState(JsonObject object, String field) throws IOException {
        String id = stringField(object, field);
        ResourceLocation key = id == null ? null : ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) throw new IOException("Unknown saved block state");
        BlockState state = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
        if (!stateId(state).equals(id)) throw new IOException("Non-default saved block state is not recoverable");
        return state;
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
                           List<Write> writes, Bounds bounds, String preFingerprint,
                           String operationType) { }

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
