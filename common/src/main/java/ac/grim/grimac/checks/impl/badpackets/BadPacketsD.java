package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

@CheckData(name = "BadPacketsD", stableKey = "grim.badpackets.invalid_pitch", description = "Sent an invalid rotation pitch outside the -90 to 90 range")
public class BadPacketsD extends Check implements PacketReceiveListener {

    private static final Verbose V = Verbose.of("pitch={f32}");

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
    };

    public BadPacketsD(GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.packetStateData.lastPacketWasTeleport) return;

        final PacketTypeCommon packetType = event.getPacketType();
        if (packetType == PacketType.Play.Client.PLAYER_ROTATION || packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            final float pitch = new WrapperPlayClientPlayerFlying(event).getLocation().getPitch();
            if (pitch > 90 || pitch < -90) {
                if (flag(V.write(verbose()).f32(pitch)) && shouldModifyPackets()) {
                    if (player.pitch > 90) player.pitch = 90;
                    if (player.pitch < -90) player.pitch = -90;

                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
        }
    }
}
