package ac.grim.grimac.platform.bukkit.initables;

import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.bukkit.events.PistonEvent;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;

public class BukkitEventManager implements StartableInitable {
    @Override
    public void start() {
        LogUtil.info("Registering packet listener for pistons... (PistonEvent)");
        PacketEvents.getAPI().getEventManager().registerListener(new PistonEvent());
    }
}
