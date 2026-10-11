package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.dragon.PacketEntityEnderDragonPart;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Ray;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAttackRange;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CheckData(name = "KillAura", stableKey = "grim.combat.killaura", description = "Attacked through blocks, with a container open, or with aim locked on the target", experimental = true)
public class KillAura extends Check implements PacketReceiveListener {

    private static final Verbose V_WALL = Verbose.of("type=wall, block={f64:%.3f}, entity={entity}");
    private static final Verbose V_INVENTORY = Verbose.of("type=inventory, entity={entity}");
    private static final Verbose V_AIM = Verbose.of("type=aim, locked={uint}/{uint}, error={f64:%.4f}");

    private static final int MAX_QUEUED_ATTACKS = 10;
    private static final int AIM_SAMPLES = 20;
    private static final int AIM_LOCKED_THRESHOLD = 15;
    private static final double AIM_MAX_ERROR = 0.15;
    private static final double AIM_MIN_DISTANCE = 1.0;
    private static final double MAX_LOCATION_UNCERTAINTY = 0.05;

    private final Int2ObjectMap<Vector3d> attackQueue = new Int2ObjectOpenHashMap<>();
    private final List<SimpleCollisionBox> blockBoxes = new ArrayList<>();
    private final boolean[] aimSamples = new boolean[AIM_SAMPLES];
    private int aimIndex;
    private int aimFilled;
    private int aimLocked;
    private double wallBuffer;

    public KillAura(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.disableGrim) {
            if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                onAttack(event, new WrapperPlayClientAttack(event).getEntityId());
            } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
                if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    onAttack(event, packet.getEntityId());
                }
            }
        }

        if (isUpdate(event.getPacketType())) {
            tickAttacks();
        }
    }

    private void onAttack(PacketReceiveEvent event, int entityId) {
        if (player.gamemode == GameMode.SPECTATOR) return;

        PacketEntity entity = player.compensatedEntities.entityMap.get(entityId);
        if (entity == null || entity instanceof PacketEntityEnderDragonPart || entity.isDead) return;

        if (player.inventory.isContainerOpenConfirmed()) {
            if (flag(V_INVENTORY.write(verbose()).uint(entityTypeId(entity))) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return;
        }

        if (player.gamemode == GameMode.CREATIVE || player.inVehicle() || entity.riding != null || !entity.isLivingEntity)
            return;

        if (attackQueue.size() < MAX_QUEUED_ATTACKS) {
            attackQueue.put(entityId, new Vector3d(player.x, player.y, player.z));
        }
    }

    private void tickAttacks() {
        if (attackQueue.isEmpty()) return;

        for (Int2ObjectMap.Entry<Vector3d> attack : attackQueue.int2ObjectEntrySet()) {
            PacketEntity entity = player.compensatedEntities.entityMap.get(attack.getIntKey());
            if (entity == null || entity.isDead) continue;

            Vector3d[] eyes = getEyePositions(attack.getValue());
            checkWall(entity, eyes);
            checkAim(entity, eyes);
        }

        attackQueue.clear();
    }

    private void checkWall(PacketEntity entity, Vector3d[] eyes) {
        SimpleCollisionBox targetBox = entity.getPossibleCollisionBoxes();
        targetBox.expand(getHitboxMargin());

        Vector3d[] looks = getLookDirections(true);
        double distance = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + 3;
        StateType heldItem = player.inventory.getHeldItem().getType().getPlacedType();

        boolean reachedTarget = false;
        double closestBlock = Double.MAX_VALUE;

        for (Vector3d eye : eyes) {
            if (ReachUtils.isVecInside(targetBox, eye)) {
                wallBuffer = Math.max(0, wallBuffer - 0.5);
                return;
            }

            for (Vector3d look : looks) {
                Vector3d intercept = ReachUtils.calculateIntercept(targetBox, eye, eye.add(look.multiply(distance))).first();
                if (intercept == null) continue;

                reachedTarget = true;
                double blockDistance = getBlockDistance(eye, intercept, heldItem);
                if (blockDistance == Double.MAX_VALUE) {
                    wallBuffer = Math.max(0, wallBuffer - 0.5);
                    return;
                }

                closestBlock = Math.min(closestBlock, blockDistance);
            }
        }

        if (!reachedTarget) return;

        if (++wallBuffer > 1) {
            flag(V_WALL.write(verbose()).f64(closestBlock).uint(entityTypeId(entity)));
        }
    }

    private double getBlockDistance(Vector3d eye, Vector3d target, StateType heldItem) {
        ClientVersion version = player.getClientVersion();

        HitData hit = WorldRayTrace.traverseBlocks(player, eye, target, (block, position) -> {
            if (block.getType().isAir()) return null;

            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            if (CollisionData.getData(block.getType()).getMovementCollisionBox(player, version, block, x, y, z).isNull()) {
                return null;
            }

            blockBoxes.clear();
            HitboxData.getBlockHitbox(player, heldItem, version, block, false, x, y, z).downCast(blockBoxes);

            HitData closest = null;
            double closestDistance = Double.MAX_VALUE;
            for (SimpleCollisionBox box : blockBoxes) {
                Pair<Vector3d, BlockFace> intercept = ReachUtils.calculateIntercept(box, eye, target);
                if (intercept.first() == null) continue;

                double interceptDistance = eye.distanceSquared(intercept.first());
                if (interceptDistance < closestDistance) {
                    closestDistance = interceptDistance;
                    closest = new HitData(position, Vector3dm.from(intercept.first()), intercept.second(), block);
                }
            }

            return closest;
        });

        if (hit == null) return Double.MAX_VALUE;

        Vector3dm location = hit.blockHitLocation();
        return eye.distance(new Vector3d(location.getX(), location.getY(), location.getZ()));
    }

    private void checkAim(PacketEntity entity, Vector3d[] eyes) {
        SimpleCollisionBox location = entity.getPossibleLocationBoxes();
        if (location.maxX - location.minX > MAX_LOCATION_UNCERTAINTY
                || location.maxY - location.minY > MAX_LOCATION_UNCERTAINTY
                || location.maxZ - location.minZ > MAX_LOCATION_UNCERTAINTY) {
            return;
        }

        SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
        double centerX = (box.minX + box.maxX) / 2;
        double centerZ = (box.minZ + box.maxZ) / 2;
        double height = box.maxY - box.minY;
        double[] targetHeights = {
                box.minY + height / 2,
                box.minY + height * (entity.getType() == EntityTypes.PLAYER ? 0.9 : 0.85)
        };

        Vector3d[] looks = getLookDirections(false);
        double bestError = Double.MAX_VALUE;

        for (Vector3d eye : eyes) {
            for (double targetY : targetHeights) {
                double dx = centerX - eye.getX();
                double dy = targetY - eye.getY();
                double dz = centerZ - eye.getZ();
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length < AIM_MIN_DISTANCE) return;

                for (Vector3d look : looks) {
                    double dot = (dx * look.getX() + dy * look.getY() + dz * look.getZ()) / length;
                    double error = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
                    bestError = Math.min(bestError, error);
                }
            }
        }

        addAimSample(bestError <= AIM_MAX_ERROR);

        if (aimFilled == AIM_SAMPLES && aimLocked >= AIM_LOCKED_THRESHOLD) {
            flag(V_AIM.write(verbose()).uint(aimLocked).uint(AIM_SAMPLES).f64(bestError));
            resetAimSamples();
        }
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
    }

    private Vector3d[] getEyePositions(Vector3d position) {
        double[] heights = player.getPossibleEyeHeights();
        Vector3d[] eyes = new Vector3d[heights.length];
        for (int i = 0; i < heights.length; i++) {
            eyes[i] = new Vector3d(position.getX(), position.getY() + heights[i], position.getZ());
        }
        return eyes;
    }

    private Vector3d[] getLookDirections(boolean clientMath) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_8)) {
            return new Vector3d[]{direction(player.yaw, player.pitch, clientMath)};
        }

        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            return new Vector3d[]{
                    direction(player.yaw, player.pitch, clientMath),
                    direction(player.lastYaw, player.pitch, clientMath)
            };
        }

        return new Vector3d[]{
                direction(player.yaw, player.pitch, clientMath),
                direction(player.lastYaw, player.pitch, clientMath),
                direction(player.lastYaw, player.lastPitch, clientMath)
        };
    }

    private Vector3d direction(float yaw, float pitch, boolean clientMath) {
        if (clientMath) {
            return Ray.calculateDirection(player, yaw, pitch);
        }

        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double xz = Math.cos(pitchRadians);
        return new Vector3d(-xz * Math.sin(yawRadians), -Math.sin(pitchRadians), xz * Math.cos(yawRadians));
    }

    private double getHitboxMargin() {
        double margin = player.getMovementThreshold();

        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            margin += 0.1;
        }

        ItemAttackRange range = player.inventory.getHeldItem().getComponentOr(ComponentTypes.ATTACK_RANGE, null);
        margin += Math.max(0, range.getHitboxMargin());

        return margin;
    }

    private int entityTypeId(PacketEntity entity) {
        return Math.max(0, entity.getType().getId(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion()));
    }
}
