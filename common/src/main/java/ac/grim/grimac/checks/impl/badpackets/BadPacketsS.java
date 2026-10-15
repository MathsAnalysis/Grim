package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;

@CheckData(name = "BadPacketsS", stableKey = "grim.badpackets.window_confirmation_not_accepted", description = "Sent a window confirmation packet marked as not accepted")
public class BadPacketsS extends Check implements PreViaPacketReceiveListener {
    public BadPacketsS(GrimPlayer player) {
        super(player);
    }

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] HANDLED_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Client.WINDOW_CONFIRMATION
    };

    @Override
    public com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.WINDOW_CONFIRMATION
                && !new WrapperPlayClientWindowConfirmation(event).isAccepted()
                && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
