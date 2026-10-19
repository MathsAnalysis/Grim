package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;

@CheckData(name = "NoSwingBreak", stableKey = "grim.breaking.no_swing_break", description = "Did not swing while breaking block", experimental = true)
public class NoSwingBreak extends Check implements BlockBreakListener, PreViaPacketReceiveListener {

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.ANIMATION,
            PacketType.Play.Client.PUNCH,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION
    };

    private boolean sentAnimation;
    private boolean sentBreak;

    public NoSwingBreak(GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != DiggingAction.CANCELLED_DIGGING) {
            sentBreak = true;
        }
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();

        if (isAnimation(packetType)) {
            sentAnimation = true;
        }

        if (isTickPacket(packetType)) {
            if (sentBreak && !sentAnimation) {
                flag();
            }

            sentAnimation = sentBreak = false;
        }
    }
}
