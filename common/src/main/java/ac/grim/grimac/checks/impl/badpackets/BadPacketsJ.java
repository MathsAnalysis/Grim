package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;

@CheckData(name = "BadPacketsJ", stableKey = "grim.badpackets.use_item_rotation_mismatch", description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements PacketReceiveListener {

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION
    };

    private float yaw;
    private float pitch;
    private int rotations;

    public BadPacketsJ(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21)
                && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem packet = new WrapperPlayClientUseItem(event);

            float currentYaw = packet.getYaw();
            float currentPitch = packet.getPitch();

            if (this.rotations > 0 && (this.yaw != currentYaw || this.pitch != currentPitch)) {
                if (!player.canSkipTicks() || this.yaw != player.yaw || this.pitch != player.pitch) {
                    while (this.rotations-- > 0) flag();
                }
                this.rotations = 0;
            }

            if (this.rotations != Integer.MAX_VALUE)
                this.rotations++;
            this.yaw = currentYaw;
            this.pitch = currentPitch;
        }

        if (this.rotations > 0 && isTickPacket(packetType)) {
            if (this.yaw != player.yaw || this.pitch != player.pitch) {
                boolean allowLast = player.canSkipTicks()
                        && (packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                        || packetType == PacketType.Play.Client.PLAYER_ROTATION);
                if (!allowLast || this.yaw != player.lastYaw || this.pitch != player.lastPitch) {
                    while (this.rotations-- > 0) flag();
                }
            }
            this.rotations = 0;
        }
    }
}
