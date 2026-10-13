package ac.grim.grimac.utils.data;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Materials;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class EntityVisibility {

    private static final int CHECK_INTERVAL = 4;
    private static final int HIDE_AFTER_BLOCKED_CHECKS = 3;
    private static final double MAX_HIDE_DISTANCE_SQUARED = 48 * 48;
    private static final double REVEAL_MARGIN = 0.5;
    private static final double[] SAMPLE_FRACTIONS = {0, 0.5, 1};
    private static final int MAX_RAYS_PER_REFRESH = 48;
    private static final int CACHE_MAX_REFRESHES = 10;
    private static final double CACHE_EPSILON = 0.05;

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

    private final GrimPlayer player;
    private final Int2ObjectOpenHashMap<TrackedPlayer> players = new Int2ObjectOpenHashMap<>();
    private int ticks;
    private boolean enabled;
    private int cursor;
    private int raysLeft;

    public EntityVisibility(GrimPlayer player) {
        this.player = player;
    }

    public boolean onPlayerSpawn(int entityId, UUID uuid, boolean legacySpawn, List<EntityData<?>> metadata) {
        TrackedPlayer tracked = new TrackedPlayer(entityId, uuid, legacySpawn, enabled);
        if (metadata != null) mergeMetadata(tracked.metadata, metadata);
        players.put(entityId, tracked);
        return tracked.hidden;
    }

    public boolean isHidden(int entityId) {
        TrackedPlayer tracked = players.get(entityId);
        return tracked != null && tracked.hidden;
    }

    public boolean onMetadata(int entityId, List<EntityData<?>> metadata) {
        TrackedPlayer tracked = players.get(entityId);
        if (tracked == null) return false;
        mergeMetadata(tracked.metadata, metadata);
        return tracked.hidden;
    }

    public boolean onEquipment(int entityId, List<Equipment> equipment) {
        TrackedPlayer tracked = players.get(entityId);
        if (tracked == null) return false;
        mergeEquipment(tracked.equipment, equipment);
        return tracked.hidden;
    }

    public void onDestroy(int entityId) {
        players.remove(entityId);
    }

    public void tick() {
        if (++ticks < CHECK_INTERVAL) return;
        ticks = 0;

        boolean active = GrimAPI.INSTANCE.getConfigManager().isExperimentalEntityHiding() && !player.disableGrim;
        if (!active) {
            if (enabled) {
                revealAll();
                players.clear();
            }
            enabled = false;
            return;
        }
        enabled = true;

        Vector3d eye = getEye();
        raysLeft = MAX_RAYS_PER_REFRESH;
        int index = 0;
        boolean finished = true;
        for (Int2ObjectMap.Entry<TrackedPlayer> entry : players.int2ObjectEntrySet()) {
            if (index++ < cursor) continue;

            TrackedPlayer tracked = entry.getValue();
            PacketEntity entity = player.compensatedEntities.entityMap.get(tracked.entityId);
            if (entity == null) continue;

            SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
            double centerX = (box.minX + box.maxX) / 2;
            double centerY = (box.minY + box.maxY) / 2;
            double centerZ = (box.minZ + box.maxZ) / 2;

            boolean visible;
            if (tracked.isCacheValid(eye, centerX, centerY, centerZ)) {
                visible = tracked.cachedVisible;
                tracked.cacheAge++;
            } else {
                if (raysLeft <= 0) {
                    finished = false;
                    cursor = index - 1;
                    break;
                }
                visible = isVisible(box, eye, centerX, centerY, centerZ);
                tracked.remember(eye, centerX, centerY, centerZ, visible);
            }

            if (tracked.hidden) {
                if (visible) reveal(tracked);
            } else if (visible) {
                tracked.blockedChecks = 0;
            } else if (++tracked.blockedChecks >= HIDE_AFTER_BLOCKED_CHECKS) {
                hide(tracked);
            }
        }
        if (finished) cursor = 0;
    }

    private void revealAll() {
        for (Int2ObjectMap.Entry<TrackedPlayer> entry : players.int2ObjectEntrySet()) {
            if (entry.getValue().hidden) reveal(entry.getValue());
        }
    }

    private Vector3d getEye() {
        double highest = 0;
        for (double height : player.getPossibleEyeHeights()) {
            highest = Math.max(highest, height);
        }
        return new Vector3d(player.x, player.y + highest, player.z);
    }

    private boolean isVisible(SimpleCollisionBox box, Vector3d eye, double centerX, double centerY, double centerZ) {
        double dx = centerX - eye.getX();
        double dy = centerY - eye.getY();
        double dz = centerZ - eye.getZ();
        if (dx * dx + dy * dy + dz * dz > MAX_HIDE_DISTANCE_SQUARED) return true;

        if (!isBlocked(eye, centerX, centerY, centerZ)) return true;

        double minX = box.minX - REVEAL_MARGIN;
        double minY = box.minY - REVEAL_MARGIN;
        double minZ = box.minZ - REVEAL_MARGIN;
        double sizeX = box.maxX - box.minX + 2 * REVEAL_MARGIN;
        double sizeY = box.maxY - box.minY + 2 * REVEAL_MARGIN;
        double sizeZ = box.maxZ - box.minZ + 2 * REVEAL_MARGIN;

        for (double fx : SAMPLE_FRACTIONS) {
            for (double fy : SAMPLE_FRACTIONS) {
                for (double fz : SAMPLE_FRACTIONS) {
                    if (!isBlocked(eye, minX + sizeX * fx, minY + sizeY * fy, minZ + sizeZ * fz)) return true;
                }
            }
        }
        return false;
    }

    private boolean isBlocked(Vector3d eye, double x, double y, double z) {
        raysLeft--;
        Vector3d point = new Vector3d(x, y, z);
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

    private void hide(TrackedPlayer tracked) {
        player.user.sendPacketSilently(new WrapperPlayServerDestroyEntities(tracked.entityId));
        tracked.hidden = true;
        tracked.blockedChecks = 0;
    }

    private void reveal(TrackedPlayer tracked) {
        TrackerData data = player.compensatedEntities.serverPositionsMap.get(tracked.entityId);
        if (data == null) return;

        float yaw = data.getXRot();
        float pitch = data.getYRot();
        Vector3d position = new Vector3d(data.getX(), data.getY(), data.getZ());

        if (tracked.legacySpawn) {
            player.user.sendPacketSilently(new WrapperPlayServerSpawnPlayer(tracked.entityId, tracked.uuid, new Location(position, yaw, pitch), new ArrayList<>(tracked.metadata)));
        } else {
            player.user.sendPacketSilently(new WrapperPlayServerSpawnEntity(tracked.entityId, Optional.of(tracked.uuid), EntityTypes.PLAYER, position, pitch, yaw, yaw, 0, Optional.empty()));
            if (!tracked.metadata.isEmpty()) {
                player.user.sendPacketSilently(new WrapperPlayServerEntityMetadata(tracked.entityId, new ArrayList<>(tracked.metadata)));
            }
        }

        if (!tracked.equipment.isEmpty()) {
            player.user.sendPacketSilently(new WrapperPlayServerEntityEquipment(tracked.entityId, new ArrayList<>(tracked.equipment)));
        }

        tracked.hidden = false;
        tracked.blockedChecks = 0;
    }

    private static void mergeMetadata(List<EntityData<?>> current, List<EntityData<?>> updates) {
        for (EntityData<?> update : updates) {
            boolean replaced = false;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).getIndex() == update.getIndex()) {
                    current.set(i, update);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) current.add(update);
        }
    }

    private static void mergeEquipment(List<Equipment> current, List<Equipment> updates) {
        for (Equipment update : updates) {
            boolean replaced = false;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).getSlot() == update.getSlot()) {
                    current.set(i, update);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) current.add(update);
        }
    }

    private static final class TrackedPlayer {
        private final int entityId;
        private final UUID uuid;
        private final boolean legacySpawn;
        private final List<EntityData<?>> metadata = new ArrayList<>();
        private final List<Equipment> equipment = new ArrayList<>();
        private boolean hidden;
        private int blockedChecks;
        private boolean cacheValid;
        private boolean cachedVisible;
        private int cacheAge;
        private double cachedEyeX, cachedEyeY, cachedEyeZ;
        private double cachedCenterX, cachedCenterY, cachedCenterZ;

        private TrackedPlayer(int entityId, UUID uuid, boolean legacySpawn, boolean hidden) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.legacySpawn = legacySpawn;
            this.hidden = hidden;
        }

        private boolean isCacheValid(Vector3d eye, double centerX, double centerY, double centerZ) {
            return cacheValid
                    && cacheAge < CACHE_MAX_REFRESHES
                    && Math.abs(eye.getX() - cachedEyeX) < CACHE_EPSILON
                    && Math.abs(eye.getY() - cachedEyeY) < CACHE_EPSILON
                    && Math.abs(eye.getZ() - cachedEyeZ) < CACHE_EPSILON
                    && Math.abs(centerX - cachedCenterX) < CACHE_EPSILON
                    && Math.abs(centerY - cachedCenterY) < CACHE_EPSILON
                    && Math.abs(centerZ - cachedCenterZ) < CACHE_EPSILON;
        }

        private void remember(Vector3d eye, double centerX, double centerY, double centerZ, boolean visible) {
            cacheValid = true;
            cachedVisible = visible;
            cacheAge = 0;
            cachedEyeX = eye.getX();
            cachedEyeY = eye.getY();
            cachedEyeZ = eye.getZ();
            cachedCenterX = centerX;
            cachedCenterY = centerY;
            cachedCenterZ = centerZ;
        }
    }
}
