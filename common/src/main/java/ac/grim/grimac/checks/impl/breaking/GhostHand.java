package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.PostFlyingBlockBreakListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.change.BlockModification;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Ray;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "GhostHand", stableKey = "grim.breaking.ghost_hand", description = "Broke a block through a solid block", experimental = true, setback = -1)
public class GhostHand extends Check implements PostFlyingBlockBreakListener {
    private static final Verbose V = Verbose.of("action={digging}, block={block}, through={block}, dist={f64:%.3f}, occluder={f64:%.3f}");

    private static final int RECENT_DIGS = 8;
    private static final int HISTORY_TICKS = 10;
    private static final double OCCLUDER_SHRINK = 0.01;
    private static final double DISTANCE_MARGIN = 0.1;

    private final Vector3i[] recentDigs = new Vector3i[RECENT_DIGS];
    private final List<Vector3i> recentChanges = new ArrayList<>();
    private int recentDigIndex;
    private Vector3i lastEvaluatedStart;
    private double buffer;

    private double violationTarget;
    private double violationOccluder;
    private StateType violationOccluderType;

    public GhostHand(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_13);
    }

    @Override
    public void onPostFlyingBlockBreak(BlockBreak blockBreak) {
        if (player.disableGrim) return;
        if (blockBreak.action != DiggingAction.START_DIGGING && blockBreak.action != DiggingAction.FINISHED_DIGGING) return;

        boolean finishesEvaluatedStart = blockBreak.action == DiggingAction.FINISHED_DIGGING && lastEvaluatedStart != null && blockBreak.position.equals(lastEvaluatedStart);
        int result = finishesEvaluatedStart ? -1 : evaluate(blockBreak);

        if (blockBreak.action == DiggingAction.START_DIGGING) {
            lastEvaluatedStart = result >= 0 ? blockBreak.position : null;
        } else {
            lastEvaluatedStart = null;
        }

        if (result > 0) {
            if (++buffer >= 2) {
                flag(V.write(verbose())
                        .uint(VerboseCodecs.enumId(blockBreak.action))
                        .sint(VerboseCodecs.block(blockBreak.block.getType(), player.getClientVersion()))
                        .sint(VerboseCodecs.block(violationOccluderType, player.getClientVersion()))
                        .f64(violationTarget)
                        .f64(violationOccluder));
            }
        } else if (result == 0) {
            buffer = Math.max(0, buffer - 0.5);
        }

        recentDigs[recentDigIndex] = blockBreak.position;
        recentDigIndex = (recentDigIndex + 1) % RECENT_DIGS;
    }

    private int evaluate(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf()) return -1;
        if (player.inVehicle()) return -1;
        if (player.gamemode == GameMode.SPECTATOR) return -1;
        if (blockBreak.block.getType().isAir()) return -1;

        ClientVersion version = player.getClientVersion();
        StateType heldItem = player.inventory.getHeldItem().getType().getPlacedType();
        SimpleCollisionBox target = new SimpleCollisionBox(blockBreak.position);
        double[] possibleEyeHeights = player.getPossibleEyeHeights();

        double minEyeHeight = Double.MAX_VALUE;
        double maxEyeHeight = -Double.MAX_VALUE;
        for (double height : possibleEyeHeights) {
            minEyeHeight = Math.min(minEyeHeight, height);
            maxEyeHeight = Math.max(maxEyeHeight, height);
        }

        SimpleCollisionBox eyePositions = new SimpleCollisionBox(player.x, player.y + minEyeHeight, player.z, player.x, player.y + maxEyeHeight, player.z);
        eyePositions.expand(player.getMovementThreshold());

        if (eyePositions.isIntersected(target) || isEyeInsideBlock(eyePositions, version, heldItem)) return -1;

        collectRecentChanges();

        List<Vector3f> possibleLookDirs = new ArrayList<>();
        possibleLookDirs.add(new Vector3f(player.lastYaw, player.pitch, 0));
        possibleLookDirs.add(new Vector3f(player.yaw, player.pitch, 0));
        possibleLookDirs.add(new Vector3f(player.lastYaw, player.lastPitch, 0));

        double shrink = player.getMovementThreshold() + OCCLUDER_SHRINK;
        double distance = player.compensatedEntities.self.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        boolean reachedTarget = false;
        double bestGap = Double.MAX_VALUE;

        for (double eyeHeight : possibleEyeHeights) {
            for (Vector3f lookDir : possibleLookDirs) {
                Ray trace = new Ray(player, player.x, player.y + eyeHeight, player.z, lookDir.getX(), lookDir.getY());
                Vector3d eye = trace.origin();
                Vector3d hit = ReachUtils.calculateIntercept(target, eye, trace.getPointAtDistance(distance)).first();
                if (hit == null) continue;

                reachedTarget = true;
                double targetDistance = eye.distance(hit);

                HitData occluder = findOccluder(eye, hit, blockBreak.position, shrink, version, heldItem);
                if (occluder == null) return 0;

                Vector3dm location = occluder.blockHitLocation();
                double occluderDistance = eye.distance(new Vector3d(location.getX(), location.getY(), location.getZ()));
                if (occluderDistance >= targetDistance - DISTANCE_MARGIN) return 0;

                double gap = targetDistance - occluderDistance;
                if (gap < bestGap) {
                    bestGap = gap;
                    violationTarget = targetDistance;
                    violationOccluder = occluderDistance;
                    violationOccluderType = occluder.state().getType();
                }
            }
        }

        return reachedTarget ? 1 : -1;
    }

    private boolean isEyeInsideBlock(SimpleCollisionBox eyePositions, ClientVersion version, StateType heldItem) {
        int minX = GrimMath.floor(eyePositions.minX);
        int minY = GrimMath.floor(eyePositions.minY);
        int minZ = GrimMath.floor(eyePositions.minZ);
        int maxX = GrimMath.floor(eyePositions.maxX);
        int maxY = GrimMath.floor(eyePositions.maxY);
        int maxZ = GrimMath.floor(eyePositions.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    WrappedBlockState block = player.compensatedWorld.getBlock(x, y, z);
                    if (block.getType().isAir()) continue;

                    if (CollisionData.getData(block.getType()).getMovementCollisionBox(player, version, block, x, y, z).isIntersected(eyePositions)) {
                        return true;
                    }

                    if (HitboxData.getBlockHitbox(player, heldItem, version, block, false, x, y, z).isIntersected(eyePositions)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private HitData findOccluder(Vector3d eye, Vector3d hit, Vector3i targetPosition, double shrink, ClientVersion version, StateType heldItem) {
        return WorldRayTrace.traverseBlocks(player, eye, hit, (block, position) -> {
            if (position.equals(targetPosition)) return null;
            if (block.getType().isAir()) return null;
            if (isRecentlyChanged(position)) return null;

            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            if (!CollisionData.getData(block.getType()).getMovementCollisionBox(player, version, block, x, y, z).isFullBlock()) {
                return null;
            }

            if (!HitboxData.getBlockHitbox(player, heldItem, version, block, false, x, y, z).isFullBlock()) {
                return null;
            }

            SimpleCollisionBox shrunk = new SimpleCollisionBox(position).expand(-shrink);
            Vector3d intercept = ReachUtils.calculateIntercept(shrunk, eye, hit).first();
            if (intercept == null) return null;

            return new HitData(position, Vector3dm.from(intercept), null, block);
        });
    }

    private void collectRecentChanges() {
        recentChanges.clear();

        int currentTick = GrimAPI.INSTANCE.getTickManager().currentTick;
        Iterable<BlockModification> modifications = player.blockHistory.getRecentModifications(modification -> currentTick - modification.tick() < HISTORY_TICKS);
        for (BlockModification modification : modifications) {
            recentChanges.add(modification.location());
        }
    }

    private boolean isRecentlyChanged(Vector3i position) {
        for (Vector3i dig : recentDigs) {
            if (dig != null && position.equals(dig)) return true;
        }

        for (Vector3i change : recentChanges) {
            if (position.equals(change)) return true;
        }

        return false;
    }
}
