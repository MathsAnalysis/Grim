package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.enums.Pose;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Materials;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.util.Arrays;
import java.util.Set;

@CheckData(name = "ESP", stableKey = "grim.combat.esp", description = "Tracked a moving, crouching player aimed through solid blocks", experimental = true)
public class AntiESP extends Check implements PacketReceiveListener {

    private static final Verbose V = Verbose.of("entity={sint}, error={f64:%.4f}, locked={uint}/{uint}");

    private static final int AIM_SAMPLES = 40;
    private static final int AIM_LOCKED_THRESHOLD = 30;
    private static final double CANDIDATE_MAX_ERROR = 10;
    private static final double LOCK_MIN_TOLERANCE = 1;
    private static final double LOCK_MAX_TOLERANCE = 8;
    private static final double LOCK_MARGIN = 0.5;
    private static final double MIN_TRACK_TURN = 0.5;
    private static final double TRACK_COHERENCE_MIN = 0.5;
    private static final double TRACK_COHERENCE_RATIO = 0.3;
    private static final double MAX_DISTANCE = 64;
    private static final double MIN_DISTANCE = 2;
    private static final double MAX_LOCATION_UNCERTAINTY = 0.05;
    private static final double MOVE_THRESHOLD = 0.01;
    private static final double MAX_TARGET_JUMP = 8;
    private static final double MAX_SELF_JUMP = 8;
    private static final int GRACE_TICKS = 20;
    private static final double SWITCH_HYSTERESIS = 3;
    private static final int MAX_GAP_TICKS = 5;
    private static final int WINDOW_MAX_TICKS = 200;
    private static final double[] SAMPLE_FRACTIONS = {0.05, 0.5, 0.95};

    private static final Set<StateType> SEE_THROUGH_BLOCKS = Set.of(
            StateTypes.ICE,
            StateTypes.FROSTED_ICE,
            StateTypes.BLUE_ICE,
            StateTypes.BARRIER,
            StateTypes.SLIME_BLOCK,
            StateTypes.HONEY_BLOCK,
            StateTypes.SPAWNER,
            StateTypes.TRIAL_SPAWNER,
            StateTypes.VAULT,
            StateTypes.BEACON,
            StateTypes.MANGROVE_ROOTS,
            StateTypes.COPPER_GRATE,
            StateTypes.EXPOSED_COPPER_GRATE,
            StateTypes.WEATHERED_COPPER_GRATE,
            StateTypes.OXIDIZED_COPPER_GRATE,
            StateTypes.WAXED_COPPER_GRATE,
            StateTypes.WAXED_EXPOSED_COPPER_GRATE,
            StateTypes.WAXED_WEATHERED_COPPER_GRATE,
            StateTypes.WAXED_OXIDIZED_COPPER_GRATE
    );

    private final boolean[] aimSamples = new boolean[AIM_SAMPLES];
    private int aimIndex;
    private int aimFilled;
    private int aimLocked;

    private int lastTargetId = -1;
    private double lastTargetX;
    private double lastTargetY;
    private double lastTargetZ;
    private double lastBearingYaw;
    private double lastBearingPitch;
    private double lastViewerYaw;
    private double lastViewerPitch;
    private double lastSelfX;
    private double lastSelfY;
    private double lastSelfZ;
    private int graceTicks;
    private int gapTicks;
    private int windowAge;

    public AntiESP(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isTickPacket(event.getPacketType())) return;

        if (player.disableGrim) {
            resetTracking();
            lastViewerYaw = player.yaw;
            lastViewerPitch = player.pitch;
            lastSelfX = player.x;
            lastSelfY = player.y;
            lastSelfZ = player.z;
            return;
        }

        tick();
    }

    private void tick() {
        if (aimFilled > 0 && ++windowAge > WINDOW_MAX_TICKS) {
            resetAimSamples();
        }

        double viewerYawDelta = wrapDegrees(player.yaw - lastViewerYaw);
        double viewerPitchDelta = player.pitch - lastViewerPitch;
        lastViewerYaw = player.yaw;
        lastViewerPitch = player.pitch;

        boolean selfJumped = Math.abs(player.x - lastSelfX) > MAX_SELF_JUMP
                || Math.abs(player.y - lastSelfY) > MAX_SELF_JUMP
                || Math.abs(player.z - lastSelfZ) > MAX_SELF_JUMP;
        lastSelfX = player.x;
        lastSelfY = player.y;
        lastSelfZ = player.z;

        if (selfJumped) {
            resetTracking();
            graceTicks = GRACE_TICKS;
        }

        if (graceTicks > 0) {
            graceTicks--;
            return;
        }

        if (player.gamemode == GameMode.SPECTATOR || player.inVehicle()) {
            resetTracking();
            return;
        }

        Vector3d[] eyes = getEyePositions();
        Vector3d look = direction(player.yaw, player.pitch);

        int targetId = -1;
        PacketEntity target = null;
        double bestError = CANDIDATE_MAX_ERROR;
        PacketEntity previous = null;
        double previousError = CANDIDATE_MAX_ERROR;
        boolean previousPresent = false;
        for (Int2ObjectMap.Entry<PacketEntity> entry : player.compensatedEntities.entityMap.int2ObjectEntrySet()) {
            PacketEntity entity = entry.getValue();
            if (entry.getIntKey() == lastTargetId && !entity.isDead) previousPresent = true;
            if (!isCandidate(entry.getIntKey(), entity)) continue;

            SimpleCollisionBox candidateLocation = entity.getPossibleLocationBoxes();
            if (candidateLocation.maxX - candidateLocation.minX > MAX_LOCATION_UNCERTAINTY
                    || candidateLocation.maxY - candidateLocation.minY > MAX_LOCATION_UNCERTAINTY
                    || candidateLocation.maxZ - candidateLocation.minZ > MAX_LOCATION_UNCERTAINTY) continue;

            double error = getAngleError(eyes[0], look, entity.getPossibleCollisionBoxes());
            if (entry.getIntKey() == lastTargetId && error < previousError) {
                previousError = error;
                previous = entity;
            }
            if (error < bestError) {
                bestError = error;
                targetId = entry.getIntKey();
                target = entity;
            }
        }

        if (previous != null && previousError <= bestError + SWITCH_HYSTERESIS) {
            targetId = lastTargetId;
            target = previous;
            bestError = previousError;
        }

        if (target == null || (previousPresent && previous == null)) {
            if (lastTargetId != -1 && ++gapTicks <= MAX_GAP_TICKS) return;
            if (target == null) {
                resetTracking();
                return;
            }
        }

        gapTicks = 0;

        SimpleCollisionBox location = target.getPossibleLocationBoxes();

        double x = (location.minX + location.maxX) / 2;
        double y = (location.minY + location.maxY) / 2;
        double z = (location.minZ + location.maxZ) / 2;

        Vector3d eye = eyes[0];
        double dx = x - eye.getX();
        double dy = y - eye.getY();
        double dz = z - eye.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double bearingYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double bearingPitch = Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));

        boolean moving = false;
        boolean tracking = false;
        if (targetId == lastTargetId) {
            double moveX = x - lastTargetX;
            double moveY = y - lastTargetY;
            double moveZ = z - lastTargetZ;
            double moveDistance = Math.sqrt(moveX * moveX + moveY * moveY + moveZ * moveZ);
            moving = moveDistance > MOVE_THRESHOLD && moveDistance < MAX_TARGET_JUMP;

            double bearingYawDelta = wrapDegrees(bearingYaw - lastBearingYaw);
            double bearingPitchDelta = bearingPitch - lastBearingPitch;
            tracking = Math.max(Math.abs(viewerYawDelta), Math.abs(viewerPitchDelta)) >= MIN_TRACK_TURN
                    && Math.abs(wrapDegrees(viewerYawDelta - bearingYawDelta)) <= getTrackCoherence(bearingYawDelta)
                    && Math.abs(viewerPitchDelta - bearingPitchDelta) <= getTrackCoherence(bearingPitchDelta);
        } else {
            resetAimSamples();
        }

        lastTargetId = targetId;
        lastTargetX = x;
        lastTargetY = y;
        lastTargetZ = z;
        lastBearingYaw = bearingYaw;
        lastBearingPitch = bearingPitch;

        SimpleCollisionBox box = target.getPossibleCollisionBoxes();
        boolean locked = moving && tracking && bestError <= getLockTolerance(box, distance) && isOccluded(eyes, box);
        addAimSample(locked);

        if (aimFilled == AIM_SAMPLES && aimLocked >= AIM_LOCKED_THRESHOLD) {
            flag(V.write(verbose()).sint(targetId).f64(bestError).uint(aimLocked).uint(AIM_SAMPLES));
            resetAimSamples();
        }
    }

    private boolean isCandidate(int entityId, PacketEntity entity) {
        return entityId != player.entityID
                && entity.getType() == EntityTypes.PLAYER
                && entity.currentPose == Pose.CROUCHING
                && !entity.isDead
                && !entity.glowing
                && !entity.hasPotionEffect(PotionTypes.GLOWING);
    }

    private double getAngleError(Vector3d eye, Vector3d look, SimpleCollisionBox box) {
        Vector3d[] points = getSamplePoints(box);
        double bestDot = -Double.MAX_VALUE;

        for (Vector3d point : points) {
            double dx = point.getX() - eye.getX();
            double dy = point.getY() - eye.getY();
            double dz = point.getZ() - eye.getZ();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < MIN_DISTANCE || length > MAX_DISTANCE) continue;

            bestDot = Math.max(bestDot, (dx * look.getX() + dy * look.getY() + dz * look.getZ()) / length);
        }

        if (bestDot == -Double.MAX_VALUE) return Double.MAX_VALUE;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, bestDot))));
    }

    private static double getLockTolerance(SimpleCollisionBox box, double distance) {
        double halfSide = Math.max(Math.max(box.maxX - box.minX, box.maxZ - box.minZ), box.maxY - box.minY) / 2;
        double subtended = Math.toDegrees(Math.atan(halfSide / distance));
        return Math.max(LOCK_MIN_TOLERANCE, Math.min(LOCK_MAX_TOLERANCE, subtended + LOCK_MARGIN));
    }

    private static double getTrackCoherence(double targetDelta) {
        return Math.max(TRACK_COHERENCE_MIN, TRACK_COHERENCE_RATIO * Math.abs(targetDelta));
    }

    private static Vector3d[] getSamplePoints(SimpleCollisionBox box) {
        double width = box.maxX - box.minX;
        double depth = box.maxZ - box.minZ;
        double height = box.maxY - box.minY;
        int side = SAMPLE_FRACTIONS.length;
        Vector3d[] points = new Vector3d[side * side * side];

        int index = 0;
        for (double heightFraction : SAMPLE_FRACTIONS) {
            for (double widthFraction : SAMPLE_FRACTIONS) {
                for (double depthFraction : SAMPLE_FRACTIONS) {
                    points[index++] = new Vector3d(box.minX + width * widthFraction, box.minY + height * heightFraction, box.minZ + depth * depthFraction);
                }
            }
        }

        return points;
    }

    private boolean isOccluded(Vector3d[] eyes, SimpleCollisionBox box) {
        Vector3d[] points = getSamplePoints(box);

        for (Vector3d eye : eyes) {
            for (Vector3d point : points) {
                if (!isBlocked(eye, point)) return false;
            }
        }

        return true;
    }

    private boolean isBlocked(Vector3d eye, Vector3d point) {
        int eyeX = GrimMath.floor(eye.getX());
        int eyeY = GrimMath.floor(eye.getY());
        int eyeZ = GrimMath.floor(eye.getZ());

        HitData hit = WorldRayTrace.traverseBlocks(player, eye, point, (block, position) -> {
            if (position.getX() == eyeX && position.getY() == eyeY && position.getZ() == eyeZ) return null;
            if (block.getType().isAir() || !isOpaque(block.getType())) return null;

            if (!CollisionData.getData(block.getType()).getMovementCollisionBox(player, player.getClientVersion(), block, position.getX(), position.getY(), position.getZ()).isFullBlock()) {
                return null;
            }

            return new HitData(position, Vector3dm.from(point), null, block);
        });

        return hit != null;
    }

    private static boolean isOpaque(StateType type) {
        return !Materials.isGlassBlock(type)
                && !Materials.isLeaves(type)
                && !SEE_THROUGH_BLOCKS.contains(type);
    }

    private Vector3d[] getEyePositions() {
        double[] heights = player.getPossibleEyeHeights();
        Vector3d[] eyes = new Vector3d[heights.length];
        for (int i = 0; i < heights.length; i++) {
            eyes[i] = new Vector3d(player.x, player.y + heights[i], player.z);
        }
        return eyes;
    }

    private static Vector3d direction(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double xz = Math.cos(pitchRadians);
        return new Vector3d(-xz * Math.sin(yawRadians), -Math.sin(pitchRadians), xz * Math.cos(yawRadians));
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    private void addAimSample(boolean locked) {
        if (aimFilled == AIM_SAMPLES) {
            if (aimSamples[aimIndex]) aimLocked--;
        } else {
            aimFilled++;
        }

        aimSamples[aimIndex] = locked;
        if (locked) aimLocked++;
        aimIndex = (aimIndex + 1) % AIM_SAMPLES;
    }

    private void resetAimSamples() {
        Arrays.fill(aimSamples, false);
        aimIndex = 0;
        aimFilled = 0;
        aimLocked = 0;
        windowAge = 0;
    }

    private void resetTracking() {
        resetAimSamples();
        lastTargetId = -1;
        gapTicks = 0;
    }
}
