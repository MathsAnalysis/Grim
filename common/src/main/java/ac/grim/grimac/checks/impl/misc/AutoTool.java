package ac.grim.grimac.checks.impl.misc;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

@CheckData(name = "AutoTool", stableKey = "grim.misc.autotool", description = "Switched held item slot away and back around a block dig within one tick")
public class AutoTool extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("from={sint}, to={sint}");

    private static final int IDLE = 0;
    private static final int SWITCHED = 1;
    private static final int DUG = 2;

    private int stage = IDLE;
    private int lastSlot;

    public AutoTool(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;
        if (player.canSkipTicks()) return;
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (isTickPacket(packetType)) {
            stage = IDLE;
            return;
        }

        if (packetType == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            final int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
            if (stage == DUG) {
                flag(V.write(verbose()).sint(lastSlot).sint(slot));
            }
            stage = SWITCHED;
            lastSlot = slot;
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_DIGGING && stage == SWITCHED) {
            final DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.START_DIGGING || action == DiggingAction.CANCELLED_DIGGING || action == DiggingAction.FINISHED_DIGGING) {
                stage = DUG;
            }
        }
    }
}
