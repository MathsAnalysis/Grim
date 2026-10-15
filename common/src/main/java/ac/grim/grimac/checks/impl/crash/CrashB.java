package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;

@CheckData(name = "CrashB", stableKey = "grim.crash.creative_while_not_creative", description = "Sent creative mode inventory click packets while not in creative mode")
public class CrashB extends Check implements PreViaPacketReceiveListener {
    public CrashB(GrimPlayer player) {
        super(player);
    }

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] HANDLED_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Client.CREATIVE_INVENTORY_ACTION
    };

    @Override
    public com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            if (player.gamemode != GameMode.CREATIVE) {
                event.setCancelled(true);
                player.onPacketCancel();
                flag(); // Could be transaction split, no need to setback though
            }
        }
    }
}
