package com.aeronauticsmcp.weditmcpbridge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Minimal bridge mod: exposes a localhost-only TCP endpoint that lets an external process
 * (the worldedit-craftscript MCP server) dispatch a Minecraft command using a real, currently
 * connected player's own CommandSourceStack.
 *
 * This exists because RCON's "execute as &lt;player&gt; run ..." does NOT make WorldEdit treat
 * the command as coming from that player (confirmed empirically: WorldEdit resolves its actor
 * from the real player connection, not from a Brigadier-substituted command source), so
 * WorldEdit-specific commands (selection info, running craftscripts, undo) silently no-op over
 * RCON even though vanilla commands work fine with execute-as. Dispatching through the player's
 * actual ServerPlayer#createCommandSourceStack() instead makes WorldEdit see it as a genuine
 * command from that player.
 */
@Mod(WeditMcpBridge.MODID)
public class WeditMcpBridge {
    public static final String MODID = "weditmcpbridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private BridgeServer bridgeServer;

    public WeditMcpBridge() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        bridgeServer = new BridgeServer(event.getServer());
        bridgeServer.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (bridgeServer != null) {
            bridgeServer.stop();
            bridgeServer = null;
        }
    }
}
