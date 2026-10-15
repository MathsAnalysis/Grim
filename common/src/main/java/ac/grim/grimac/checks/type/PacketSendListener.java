package ac.grim.grimac.checks.type;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

public interface PacketSendListener {
    void onPacketSend(PacketSendEvent event);

    default PacketTypeCommon[] getHandledSendPacketTypes() {
        return null;
    }
}
