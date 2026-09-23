package com.aeronauticsmcp.weditmcpbridge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Local bridge for WorldEdit actor context, selection inspection, and small bounded
 * construction operations. The TCP endpoint binds to loopback only. All Minecraft
 * and WorldEdit access runs on the server thread.
 *
 * This exists because RCON's "execute as &lt;player&gt; run ..." does NOT make WorldEdit treat
 * the command as coming from that player (confirmed empirically: WorldEdit resolves its actor
 * from the real player connection, not from a Brigadier-substituted command source), so
 * WorldEdit-specific commands (selection info, running craftscripts, undo) silently no-op over
 * RCON even though vanilla commands work fine with execute-as. Dispatching through the player's
 * actual ServerPlayer actor lets WorldEdit resolve the correct player's selection.
 * Unbounded legacy command dispatch is disabled unless explicitly enabled at startup.
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
