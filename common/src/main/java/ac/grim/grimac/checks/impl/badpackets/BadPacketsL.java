package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

@CheckData(name = "BadPacketsL", stableKey = "grim.badpackets.invalid_dig", description = "Sent impossible dig packet")
public class BadPacketsL extends Check implements PacketReceiveListener {

    private static final Verbose V =
            Verbose.of("pos={mcpos}, face={sint}, sequence={sint}, action={digging_lower}");

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_DIGGING
    };

    public BadPacketsL(GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            final WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);

            if (packet.getAction() == DiggingAction.START_DIGGING || packet.getAction() == DiggingAction.FINISHED_DIGGING || packet.getAction() == DiggingAction.CANCELLED_DIGGING)
                return;

            final boolean directionChange = packet.getAction() == DiggingAction.CHANGE_DESTROY_DIRECTION;
            final boolean allowLegacyFace = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)
                    && packet.getAction() == DiggingAction.RELEASE_USE_ITEM;
            final boolean isValidFace = directionChange ? packet.getBlockFaceId() >= 0 && packet.getBlockFaceId() <= 5
                    : packet.getBlockFaceId() == 0 || (allowLegacyFace && packet.getBlockFaceId() == 255);

            if (!isValidFace
                    || (!directionChange && (packet.getBlockPosition().getX() != 0
                    || packet.getBlockPosition().getY() != 0
                    || packet.getBlockPosition().getZ() != 0))
                    || packet.getSequence() != 0) {
                final Vector3i pos = packet.getBlockPosition();
                var buf = V.write(verbose())
                        .mcPos(pos.getX(), pos.getY(), pos.getZ())
                        .sint(packet.getBlockFaceId())
                        .sint(packet.getSequence())
                        .uint(VerboseCodecs.enumId(packet.getAction()));
                if (flag(buf) && shouldModifyPackets() && canCancel(packet.getAction())) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
        }
    }
}
