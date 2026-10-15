package ac.grim.grimac.checks.impl.groundspoof;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.GhostBlockDetector;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.ArrayList;
import java.util.List;

// Catches NoFalls for LOOK and GROUND packets
// This check runs AFTER the predictions
@CheckData(name = "NoFall", stableKey = "grim.groundspoof.no_fall", description = "Sent an on-ground packet while not colliding with the ground", setback = 10, decay = 0.05)
public class NoFall extends Check implements PacketReceiveListener {

    public boolean flipPlayerGroundStatus = false;

    public NoFall(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
            // The player hasn't spawned yet
            if (player.getSetbackTeleportUtil().insideUnloadedChunk()) return;
            // The player has already been flagged, and
            if (player.getSetbackTeleportUtil().blockOffsets) return;

            // Exempt non-survival modes
            if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.CREATIVE || player.isFlying) return;

            // Exempt vehicles, climbing, liquids, gliding
            if (player.inVehicle() || player.isClimbing || player.lastWasClimbing > 0
                    || player.wasTouchingWater || player.wasWasTouchingWater || player.wasTouchingLava
                    || player.isGliding || player.wasGliding) return;

            // Exempt ground uncertainty (stepping, pistons, beds, etc.)
            if (player.exemptOnGround() || player.uncertaintyHandler.onGroundUncertain) return;

            // Exempt recent teleports (prevents desync during/after teleports)
            if (player.packetStateData.lastPacketWasTeleport || player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(5)) return;

            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);

            // If the player claims to be on the ground
            // Run this code IFF the player doesn't send the position, as that won't get processed by predictions
            if (wrapper.isOnGround() && !wrapper.hasPositionChanged()) {
                // If the player was predicted to be on the ground and position hasn't changed, they are legitimately on the ground
                if (player.onGround || player.lastOnGround) {
                    reward();
                    return;
                }

                if (!isNearGround(wrapper.isOnGround())) { // If player isn't near ground
                    // 1.8 boats have a mind on their own... only flag if they're not near a boat or are on 1.9+
                    if (!GhostBlockDetector.isGhostBlock(player)) {
                        flagWithSetback();
                        if (shouldModifyPackets()) {
                            wrapper.setOnGround(false);
                            event.markForReEncode(true);
                        }
                    }
                } else {
                    reward();
                }
            }
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
            // The prediction based NoFall check (that runs before us without the packet)
            // has asked us to flip the player's onGround status
            // This happens to make both checks use the same logic... and
            // since we don't have access to modify the packet with prediction based checks
            // I could add that feature but ehh... this works and is better anyway.
            if (flipPlayerGroundStatus) {
                flipPlayerGroundStatus = false;
                if (shouldModifyPackets()) {
                    wrapper.setOnGround(!wrapper.isOnGround());
                    event.markForReEncode(true);
                }
            }
        }
    }

    private boolean isNearGround(boolean onGround) {
        if (!onGround) return true;

        double step = Math.max(0.6, player.getMaxUpStep());
        SimpleCollisionBox feetBB = new SimpleCollisionBox(
                player.x - 0.4, player.y - step, player.z - 0.4,
                player.x + 0.4, player.y + 0.1, player.z + 0.4
        );

        return checkForBoxes(feetBB);
    }

    private boolean checkForBoxes(SimpleCollisionBox feetBB) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        Collisions.getCollisionBoxes(player, feetBB, boxes, false);

        if (!boxes.isEmpty()) {
            return true;
        }

        return player.compensatedWorld.isNearHardEntity(feetBB.copy().expand(2));
    }
}
