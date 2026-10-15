package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.PostFlyingBlockBreakListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.nmsutil.Ray;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "RotationBreak", stableKey = "grim.breaking.rotation_break", description = "Tried to break a block without looking at it", experimental = true)
public class RotationBreak extends Check implements PostFlyingBlockBreakListener {
    private static final Verbose V = Verbose.of("[post-flying], action={digging}");

    private double flagBuffer = 0;
    private Vector3i lastEvaluatedStart;

    public RotationBreak(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPostFlyingBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf())
            return; // you don't send flying packets when spectating entities
        if (player.inVehicle()) return; // falses
        if (blockBreak.action == DiggingAction.CANCELLED_DIGGING) return; // falses

        // In survival mode, breaking a block takes time. If the player was legitimately looking at the block
        // when they started digging (START_DIGGING), do not flag them if they turned their camera away
        // right as the block finished breaking (FINISHED_DIGGING).
        if (blockBreak.action == DiggingAction.FINISHED_DIGGING
                && lastEvaluatedStart != null
                && blockBreak.position.equals(lastEvaluatedStart)) {
            lastEvaluatedStart = null;
            flagBuffer = Math.max(0, flagBuffer - 0.5);
            return;
        }

        boolean hit = didRayTraceHit(blockBreak);

        if (blockBreak.action == DiggingAction.START_DIGGING) {
            lastEvaluatedStart = hit ? blockBreak.position : null;
        } else {
            lastEvaluatedStart = null;
        }

        if (hit) {
            flagBuffer = Math.max(0, flagBuffer - 0.5);
        } else {
            flagBuffer += 1.0;
            if (flagBuffer >= 3.0) {
                if (flag(V.write(verbose())
                        .uint(VerboseCodecs.enumId(blockBreak.action))) && shouldModifyPackets()) {
                    player.resyncPosition(blockBreak.position, blockBreak.sequence);
                    player.onPacketCancel();
                }
            }
        }
    }

    private boolean didRayTraceHit(BlockBreak blockBreak) {
        SimpleCollisionBox box = new SimpleCollisionBox(blockBreak.position);
        SimpleCollisionBox combined = blockBreak.getCombinedBox();

        // Encompass both the 1x1x1 integer block cube and the custom block shape
        double minX = Math.min(box.minX, combined.minX);
        double minY = Math.min(box.minY, combined.minY);
        double minZ = Math.min(box.minZ, combined.minZ);
        double maxX = Math.max(box.maxX, combined.maxX);
        double maxY = Math.max(box.maxY, combined.maxY);
        double maxZ = Math.max(box.maxZ, combined.maxZ);
        box = new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);

        final double[] possibleEyeHeights = player.getPossibleEyeHeights();

        // Check if player's eyes are in or intersecting the block
        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = Double.MIN_VALUE;
        for (double height : possibleEyeHeights) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        double moveThreshold = player.getMovementThreshold();
        SimpleCollisionBox eyePositions = new SimpleCollisionBox(
                Math.min(player.x, player.lastX),
                Math.min(player.y, player.lastY) + minEyeHeight,
                Math.min(player.z, player.lastZ),
                Math.max(player.x, player.lastX),
                Math.max(player.y, player.lastY) + maxEyeHeight,
                Math.max(player.z, player.lastZ)
        );
        eyePositions.expand(moveThreshold + 0.05);

        // If the player is inside a block, they can ray trace through it
        if (eyePositions.isIntersected(box)) {
            return true;
        }

        // Expand the block box slightly to account for rotation sweep, grazing angles and floating point error
        box.expand(moveThreshold + 0.08);

        List<Vector3f> possibleLookDirs = new ArrayList<>();
        possibleLookDirs.add(new Vector3f(player.yaw, player.pitch, 0));
        possibleLookDirs.add(new Vector3f(player.lastYaw, player.pitch, 0));
        possibleLookDirs.add(new Vector3f(player.yaw, player.lastPitch, 0));
        possibleLookDirs.add(new Vector3f(player.lastYaw, player.lastPitch, 0));

        // When turning quickly, interpolate look directions across the mouse movement arc
        float deltaYaw = (player.yaw - player.lastYaw + 540.0f) % 360.0f - 180.0f;
        float deltaPitch = player.pitch - player.lastPitch;
        float maxDelta = Math.max(Math.abs(deltaYaw), Math.abs(deltaPitch));

        if (maxDelta > 1.5f) {
            // Adaptive sampling: sample points along the rotation stroke
            int steps = Math.min(10, Math.max(3, (int) (maxDelta / 4.0f)));
            for (int i = 1; i < steps; i++) {
                float alpha = (float) i / (float) steps;
                float sampleYaw = player.lastYaw + alpha * deltaYaw;
                float samplePitch = player.lastPitch + alpha * deltaPitch;
                possibleLookDirs.add(new Vector3f(sampleYaw, samplePitch, 0));
            }
        }

        // Reach distance with movement tolerance
        double baseReach = player.compensatedEntities.self.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        double distance = baseReach + Math.hypot(moveThreshold, moveThreshold) + 0.35;

        // Test from current eye position and previous eye position if player moved
        List<Vector3d> origins = new ArrayList<>();
        origins.add(new Vector3d(player.x, player.y, player.z));
        if (player.x != player.lastX || player.y != player.lastY || player.z != player.lastZ) {
            origins.add(new Vector3d(player.lastX, player.lastY, player.lastZ));
        }

        for (Vector3d origin : origins) {
            for (double d : possibleEyeHeights) {
                Vector3d starting = new Vector3d(origin.getX(), origin.getY() + d, origin.getZ());
                for (Vector3f lookDir : possibleLookDirs) {
                    Ray trace = new Ray(player, starting.getX(), starting.getY(), starting.getZ(), lookDir.getX(), lookDir.getY());
                    Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, trace.origin(), trace.getPointAtDistance(distance));

                    if (intercept.first() != null) return true;
                }
            }
        }

        return false;
    }
}
