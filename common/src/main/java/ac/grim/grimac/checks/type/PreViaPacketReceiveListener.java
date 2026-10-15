package ac.grim.grimac.checks.type;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

public interface PreViaPacketReceiveListener {
    void onPreViaPacketReceive(PacketReceiveEvent event);

    default PacketTypeCommon[] getHandledPacketTypes() {
        return null;
    }
}
