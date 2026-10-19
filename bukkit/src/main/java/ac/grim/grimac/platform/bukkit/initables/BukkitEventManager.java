package ac.grim.grimac.platform.bukkit.initables;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.events.PistonEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class BukkitEventManager implements StartableInitable {
    @Override
    public void start() {
        LogUtil.info("Registering packet listener for pistons... (PistonEvent)");
        PacketEvents.getAPI().getEventManager().registerListener(new PistonEvent());

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onTeleport(PlayerTeleportEvent event) {
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
                    GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getPlayer().getUniqueId());
                    if (player != null) {
                        player.uncertaintyHandler.lastEnderPearlTeleport.reset();
                    }
                }
            }
        }, GrimACBukkitLoaderPlugin.LOADER);
    }
}
