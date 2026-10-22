package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.enums.Pose;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

import java.util.Collections;

@CheckData(name = "SprintB", stableKey = "grim.sprint.sneaking", description = "Sprinting while sneaking or crawling", setback = 5, experimental = true)
public class SprintB extends Check implements PacketReceiveListener, PostPredictionListener {
    // https://bugs.mojang.com/browse/MC-152728
    // 1.14.2 - 1.21.3 clients keep sprinting through slow movement if the sprint started before it,
    // so only a sprint that starts during slow movement can be checked on those versions.
    public boolean startedSprintingBeforeSlowMovement = false;
    private boolean wasSlowMovement = false;

    public SprintB(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            if (new WrapperPlayClientEntityAction(event).getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                startedSprintingBeforeSlowMovement = false;
            }
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        final boolean slowMovement = player.isSlowMovement && player.sneakingSpeedMultiplier < 0.8f;
        if (slowMovement && !wasSlowMovement) {
            startedSprintingBeforeSlowMovement = player.isSprinting;
        }
        wasSlowMovement = slowMovement;

        if (slowMovement && predictionComplete.isChecked()) {
            ClientVersion version = player.getClientVersion();

            // https://bugs.mojang.com/browse/MC-152728
            if (startedSprintingBeforeSlowMovement && version.isNewerThanOrEquals(ClientVersion.V_1_14_2) && version.isOlderThan(ClientVersion.V_1_21_4)) {
                return;
            }

            // you can sneak and swim in 1.13 - 1.14.1
            if (player.isSwimming && version.isNewerThanOrEquals(ClientVersion.V_1_13) && version.isOlderThanOrEquals(ClientVersion.V_1_14_1)) {
                return;
            }

            // https://github.com/GrimAnticheat/Grim/issues/1932
            if (version.isNewerThanOrEquals(ClientVersion.V_1_14) && player.wasFlying && player.lastPose == Pose.FALL_FLYING && !player.isGliding) {
                return;
            }

            // https://github.com/GrimAnticheat/Grim/issues/1948
            if (version == ClientVersion.V_1_21_4 && (Collections.max(player.uncertaintyHandler.pistonX) != 0
                    || Collections.max(player.uncertaintyHandler.pistonY) != 0
                    || Collections.max(player.uncertaintyHandler.pistonZ) != 0)) {
                return;
            }

            if (!player.wasTouchingWater || version.isOlderThan(ClientVersion.V_1_13)) {
                return;
            }

            if (player.isSprinting) {
                flagWithSetback();
            } else reward();
        }
    }
}
