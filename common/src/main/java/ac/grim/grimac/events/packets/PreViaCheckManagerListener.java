package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import org.jetbrains.annotations.NotNull;

public class PreViaCheckManagerListener extends PacketListenerAbstract {

    public PreViaCheckManagerListener() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    @Override
    public void onPacketReceive(final @NotNull PacketReceiveEvent event) {
        final ConnectionState state = event.getConnectionState();
        if (state != ConnectionState.PLAY && state != ConnectionState.CONFIGURATION) return;

        final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) return;

        player.checkManager.onPreViaPacketReceive(event);
    }

    @Override
    public void onPacketSend(final @NotNull PacketSendEvent event) {
        final ConnectionState state = event.getConnectionState();
        if (state != ConnectionState.PLAY) {
            if (state == ConnectionState.CONFIGURATION) {
                final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
                if (player != null) player.checkManager.onPreViaPacketSend(event);
            }
            return;
        }

        final GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) return;

        final PacketTypeCommon packetType = event.getPacketType();
        if (packetType == PacketType.Play.Server.OPEN_WINDOW || packetType == PacketType.Play.Server.OPEN_HORSE_WINDOW) {
            player.sendTransaction();
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.serverOpenedInventoryThisTick = true);
        } else if (packetType == PacketType.Play.Server.BUNDLE) {
            player.packetStateData.sendingBundlePacket = !player.packetStateData.sendingBundlePacket;
        }

        player.checkManager.onPreViaPacketSend(event);
    }
}
