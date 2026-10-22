package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

@CheckData(name = "SprintC", stableKey = "grim.sprint.using_item", description = "Sprinting while using an item", setback = 5, experimental = true)
public class SprintC extends Check implements PacketReceiveListener, PostPredictionListener {
    // https://bugs.mojang.com/browse/MC-152728
    // 1.14.2 - 1.21.3 clients keep sprinting while using an item if the sprint started before the use,
    // so only a sprint that starts during the item use can be checked on those versions.
    public boolean startedSprintingBeforeUse = false;
    private boolean wasUsingItem = false;
    private boolean flaggedLastTick = false;

    public SprintC(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            if (new WrapperPlayClientEntityAction(event).getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                startedSprintingBeforeUse = false;
            }
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        final boolean usingItem = player.packetStateData.isSlowedByUsingItem();
        if (usingItem && !wasUsingItem) {
            startedSprintingBeforeUse = player.isSprinting;
        }
        wasUsingItem = usingItem;

        if (usingItem) {
            ClientVersion client = player.getClientVersion();

            // https://bugs.mojang.com/browse/MC-152728
            if (startedSprintingBeforeUse && client.isNewerThanOrEquals(ClientVersion.V_1_14_2) && client.isOlderThan(ClientVersion.V_1_21_4)) {
                flaggedLastTick = false;
                return;
            }

            if (!player.wasTouchingWater || client.isOlderThan(ClientVersion.V_1_13)) {
                flaggedLastTick = false;
                return;
            }

            if (player.isSprinting) {
                if (flaggedLastTick) flagWithSetback();
                flaggedLastTick = true;
            } else {
                reward();
                flaggedLastTick = false;
            }
        }
    }
}
