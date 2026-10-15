package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.protocol.player.GameMode;

@CheckData(name = "GroundSpoof", stableKey = "grim.groundspoof.fake", description = "Claimed to be on ground when predicted otherwise", setback = 10, decay = 0.05)
public class GroundSpoof extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("claimed {bool}");

    public GroundSpoof(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // Exemptions
        // Don't check non-survival / flying players
        if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.CREATIVE || player.isFlying)
            return;
        // Don't check this long list of ground exemptions
        if (player.exemptOnGround() || !predictionComplete.isChecked()) return;
        // Don't check if the player was on a ghost block
        if (player.getSetbackTeleportUtil().blockOffsets) return;
        // Teleport safety margin
        if (player.packetStateData.lastPacketWasTeleport || player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(5)) return;
        // Uncertainty, climbing, liquids, gliding
        if (player.uncertaintyHandler.onGroundUncertain || player.isClimbing || player.lastWasClimbing > 0
                || player.wasTouchingWater || player.wasWasTouchingWater || player.wasTouchingLava
                || player.isGliding || player.wasGliding) return;

        boolean claimed = player.clientClaimsLastOnGround;
        if (claimed != player.onGround) {
            flagWithSetback(V.write(verbose()).bool(claimed));
            player.checkManager.getNoFall().flipPlayerGroundStatus = true;
        } else {
            reward();
        }
    }
}
