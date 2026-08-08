package com.aeronauticsmcp.weditmcpbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Localhost-only TCP server. One request-response per connection: a single line of JSON in,
 * a single line of JSON out. Never bound to anything but 127.0.0.1 — this is purely a local
 * IPC channel between this mod and the worldedit-craftscript MCP server running on the same
 * machine; it is not meant to be reachable from the network.
 *
 * Commands are dispatched through WorldEdit's own event bus (WorldEdit.getInstance()
 * .getEventBus().post(new CommandEvent(actor, "//command"))), exactly matching how a real
 * player's chat-typed "//" command is handled internally. This was necessary because neither
 * RCON's "execute as &lt;player&gt;" nor vanilla Brigadier dispatch (even with a real player's
 * own CommandSourceStack) reaches WorldEdit's "//"-prefixed commands at all — WorldEdit
 * intercepts those via its own chat-handling hook, entirely separate from Brigadier. See the
 * project README for the full investigation.
 */
class BridgeServer {
    private static final Gson GSON = new Gson();

    private final MinecraftServer server;
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    BridgeServer(MinecraftServer server) {
        this.server = server;
    }

    void start() {
        int port = 25577;
        String portEnv = System.getenv("WEDIT_BRIDGE_PORT");
        if (portEnv != null) {
            try {
                port = Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }

        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            WeditMcpBridge.LOGGER.error("Failed to start bridge server on port {}: {}", port, e.getMessage());
            return;
        }

        final int boundPort = port;
        WeditMcpBridge.LOGGER.info("weditmcpbridge listening on 127.0.0.1:{}", boundPort);

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
                connectionExecutor.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    WeditMcpBridge.LOGGER.warn("weditmcpbridge accept error: {}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return;

            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            String username = request.get("username").getAsString();
            String command = request.get("command").getAsString();

            String responseJson = dispatch(username, command);

            OutputStream out = socket.getOutputStream();
            out.write((responseJson + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            WeditMcpBridge.LOGGER.warn("weditmcpbridge connection error: {}", e.getMessage());
        }
    }

    private String dispatch(String username, String command) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        server.execute(() -> {
            JsonObject result = new JsonObject();
            try {
                ServerPlayer player = server.getPlayerList().getPlayerByName(username);
                if (player == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "Player not online: " + username);
                } else {
                    Actor actor = NeoForgeAdapter.adaptPlayer(player);
                    CommandEvent event = new CommandEvent(actor, command);
                    WorldEdit.getInstance().getEventBus().post(event);
                    result.addProperty("ok", true);
                    result.addProperty("handled", event.isCancelled());
                }
            } catch (Exception e) {
                result.addProperty("ok", false);
                result.addProperty("error", String.valueOf(e.getMessage()));
            }
            future.complete(result);
        });

        try {
            JsonObject result = future.get(10, TimeUnit.SECONDS);
            return GSON.toJson(result);
        } catch (Exception e) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Timed out or failed waiting for server thread: " + e.getMessage());
            return GSON.toJson(result);
        }
    }
}
