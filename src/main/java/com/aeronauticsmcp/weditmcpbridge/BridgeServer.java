package com.aeronauticsmcp.weditmcpbridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.world.World;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only implementation channel for the local WorldEdit MCP server. Each connection
 * carries one bounded JSON request and one JSON response. WorldEdit state is read on the
 * Minecraft server thread; no socket wait runs on that thread.
 */
class BridgeServer {
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final int MAX_COMMAND_LENGTH = 4096;
    private static final int SERVER_THREAD_TIMEOUT_SECONDS = 10;
    private static final boolean ALLOW_UNBOUNDED_LEGACY_COMMANDS =
            Boolean.parseBoolean(System.getenv("WEDIT_BRIDGE_ALLOW_LEGACY_COMMANDS"));

    private final MinecraftServer server;
    private final BoundedBuildService builds;
    private final ExecutorService connectionExecutor = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "weditmcpbridge-connection");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private ServerSocket serverSocket;
    private Thread acceptThread;

    BridgeServer(MinecraftServer server) {
        this.server = server;
        this.builds = new BoundedBuildService(server);
    }

    void start() {
        int port = 25577;
        String portEnv = System.getenv("WEDIT_BRIDGE_PORT");
        if (portEnv != null) {
            try {
                port = Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ignored) {
                // Keep the loopback default port.
            }
        }
        try {
            serverSocket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            WeditMcpBridge.LOGGER.error("Failed to start bridge server on port {}: {}", port, e.getMessage());
            return;
        }
        WeditMcpBridge.LOGGER.info("weditmcpbridge listening on 127.0.0.1:{}", port);
        acceptThread = new Thread(this::acceptLoop, "weditmcpbridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        connectionExecutor.shutdownNow();
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    connectionExecutor.execute(() -> handleConnection(socket));
                } catch (RejectedExecutionException e) {
                    try (socket) {
                        writeResponse(socket, error("busy", "Bridge request queue is full"));
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    WeditMcpBridge.LOGGER.warn("weditmcpbridge accept error: {}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            socket.setSoTimeout(12_000);
            String line = readBoundedLine(socket.getInputStream());
            if (line == null) return;
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            String username = stringField(request, "username");
            if (username == null || !username.matches("[A-Za-z0-9_]{1,16}")) {
                writeResponse(socket, error("invalid_request", "Invalid Minecraft username"));
                return;
            }

            String action = stringField(request, "action");
            JsonObject response;
            if ("get_selection".equals(action)) {
                response = onServerThread(username, this::getSelection);
            } else if ("select_cuboid".equals(action)) {
                response = onServerThread(username, player -> selectCuboid(player, request));
            } else if ("preview_set_blocks".equals(action)) {
                response = onServerThread(username, player -> builds.preview(player, request));
            } else if ("preview_fill_cuboid".equals(action)) {
                response = onServerThread(username, player -> builds.previewFillCuboid(player, request));
            } else if ("apply_preview".equals(action)) {
                response = onServerThread(username, player -> builds.apply(player, request));
            } else if ("undo_operation".equals(action)) {
                response = onServerThread(username, player -> builds.undo(player, request));
            } else if ("get_pending_operation".equals(action)) {
                response = onServerThread(username, builds::pending);
            } else if ("get_operation_status".equals(action)) {
                response = onServerThread(username, player -> builds.operationStatus(player, request));
            } else if ("list_operations".equals(action)) {
                response = onServerThread(username, builds::listOperations);
            } else if (action == null && request.has("command")) {
                // Legacy dispatch can execute unrestricted WorldEdit commands. Disabled by default.
                String command = stringField(request, "command");
                if (!ALLOW_UNBOUNDED_LEGACY_COMMANDS) {
                    response = error("legacy_disabled", "Unbounded command dispatch is disabled");
                } else if (command == null || command.isBlank() || command.length() > MAX_COMMAND_LENGTH) {
                    response = error("invalid_request", "Invalid or oversized command");
                } else {
                    response = onServerThread(username, player -> dispatchCommand(player, command));
                }
            } else {
                response = error("invalid_request", "Unknown bridge action");
            }
            writeResponse(socket, response);
        } catch (Exception e) {
            try {
                if (!socket.isClosed()) writeResponse(socket, error("invalid_request", "Malformed bridge request"));
            } catch (IOException ignored) {
            }
            WeditMcpBridge.LOGGER.warn("weditmcpbridge connection error: {}", e.getMessage());
        }
    }

    private JsonObject onServerThread(String username, BridgeAction action) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        // 0=queued, 1=started, 2=cancelled before start. CAS prevents a timeout
        // racing a queued mutation into execution after we claimed cancellation.
        AtomicInteger phase = new AtomicInteger(0);
        try {
            server.execute(() -> {
                if (!phase.compareAndSet(0, 1)) return;
                try {
                    ServerPlayer player = server.getPlayerList().getPlayerByName(username);
                    if (player == null) {
                        future.complete(error("player_offline", "Player not online: " + username));
                    } else {
                        JsonObject result = action.run(player);
                        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
                        future.complete(result);
                    }
                } catch (Exception e) {
                    future.complete(error("internal_error", String.valueOf(e.getMessage())));
                }
            });
        } catch (Exception e) {
            return error("server_unavailable", "Could not schedule on Minecraft server thread");
        }
        try {
            return future.get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (phase.compareAndSet(0, 2)) {
                future.complete(error("timeout", "Timed out before Minecraft server thread started the action"));
                return future.join();
            }
            JsonObject completed = future.getNow(null);
            if (completed != null) return completed;
            return error("completion_unknown", "Action started but did not respond before timeout; query get_pending_operation before retrying");
        } catch (Exception e) {
            if (phase.compareAndSet(0, 2)) {
                future.complete(error("server_unavailable", "Minecraft server thread did not start the action"));
                return future.join();
            }
            JsonObject completed = future.getNow(null);
            if (completed != null) return completed;
            return error("completion_unknown", "Action may have run; query get_pending_operation before retrying");
        }
    }

    private JsonObject dispatchCommand(ServerPlayer player, String command) {
        Actor actor = NeoForgeAdapter.adaptPlayer(player);
        CommandEvent event = new CommandEvent(actor, command);
        WorldEdit.getInstance().getEventBus().post(event);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("handled", event.isCancelled());
        result.addProperty("status", "dispatched");
        result.addProperty("completed", false);
        return result;
    }

    @FunctionalInterface
    private interface BridgeAction {
        JsonObject run(ServerPlayer player);
    }

    private JsonObject getSelection(ServerPlayer player) {
        Actor actor = NeoForgeAdapter.adaptPlayer(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
        World selectionWorld = session.getSelectionWorld();
        if (selectionWorld == null) {
            return error("selection_incomplete", "Player has no WorldEdit selection");
        }
        try {
            // Use the selection's own world. Calling getRegionSelector(player.getWorld()) first
            // could silently clear a valid selection in another dimension.
            Region region = session.getSelection(selectionWorld);
            String type = session.getRegionSelector(selectionWorld).getTypeName();
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            JsonObject selection = new JsonObject();
            selection.addProperty("dimension", NeoForgeAdapter.adapt(selectionWorld)
                    .dimension().location().toString());
            selection.addProperty("selection_type", type);
            selection.add("min", point(min));
            selection.add("max", point(max));
            selection.addProperty("width", region.getWidth());
            selection.addProperty("height", region.getHeight());
            selection.addProperty("length", region.getLength());
            selection.addProperty("block_volume", region.getVolume());
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("selection", selection);
            return result;
        } catch (IncompleteRegionException e) {
            return error("selection_incomplete", "WorldEdit selection is not fully defined");
        } catch (Exception e) {
            return error("selection_unavailable", String.valueOf(e.getMessage()));
        }
    }

    private JsonObject selectCuboid(ServerPlayer player, JsonObject request) {
        BlockPos min = pointField(request, "min");
        BlockPos max = pointField(request, "max");
        if (min == null || max == null) {
            return error("invalid_request", "min and max must have integer x/y/z coordinates");
        }
        JsonObject rejected = builds.validateSelection(player, min, max);
        if (rejected != null) return rejected;
        Actor actor = NeoForgeAdapter.adaptPlayer(player);
        World world = NeoForgeAdapter.adapt(player.serverLevel());
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
        session.setRegionSelector(world, new CuboidRegionSelector(world,
                BlockVector3.at(min.getX(), min.getY(), min.getZ()),
                BlockVector3.at(max.getX(), max.getY(), max.getZ())));
        JsonObject result = getSelection(player);
        if (result.get("ok").getAsBoolean()) {
            result.addProperty("status", "selected");
            result.addProperty("completed", true);
        }
        return result;
    }

    private static BlockPos pointField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) return null;
        JsonObject point = value.getAsJsonObject();
        Integer x = integerField(point, "x");
        Integer y = integerField(point, "y");
        Integer z = integerField(point, "z");
        return x == null || y == null || z == null ? null : new BlockPos(x, y, z);
    }

    private static Integer integerField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        String text = value.getAsString();
        if (!text.matches("-?(0|[1-9][0-9]*)")) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonObject point(BlockVector3 point) {
        JsonObject result = new JsonObject();
        result.addProperty("x", point.x());
        result.addProperty("y", point.y());
        result.addProperty("z", point.z());
        return result;
    }

    private static String stringField(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()) return null;
        return object.get(field).getAsString();
    }

    private static JsonObject error(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.add("dimension", JsonNull.INSTANCE);
        result.addProperty("error_code", code);
        result.addProperty("error", message);
        return result;
    }

    private static String readBoundedLine(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) != -1) {
            if (next == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() == MAX_REQUEST_BYTES) throw new IOException("Bridge request exceeds size limit");
            bytes.write(next);
        }
        return null;
    }

    private static void writeResponse(Socket socket, JsonObject response) throws IOException {
        socket.getOutputStream().write((GSON.toJson(response) + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
}
