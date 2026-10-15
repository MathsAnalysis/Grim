package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PistonData;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.nmsutil.Materials;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class PistonEvent extends PacketListenerAbstract {

    private static final double MAX_HORIZONTAL_DISTANCE = 24.0;
    private static final double MAX_VERTICAL_DISTANCE = 64.0;
    private static final int MAX_PUSH_BLOCKS = 12;

    private static final BlockFace[] ALL_FACES = new BlockFace[]{
            BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };

    private static final Set<StateType> IMMOVABLE_TYPES = Collections.newSetFromMap(new IdentityHashMap<>());

    static {
        IMMOVABLE_TYPES.add(StateTypes.OBSIDIAN);
        IMMOVABLE_TYPES.add(StateTypes.CRYING_OBSIDIAN);
        IMMOVABLE_TYPES.add(StateTypes.RESPAWN_ANCHOR);
        IMMOVABLE_TYPES.add(StateTypes.REINFORCED_DEEPSLATE);
        IMMOVABLE_TYPES.add(StateTypes.SPAWNER);
        IMMOVABLE_TYPES.add(StateTypes.PISTON_HEAD);
        IMMOVABLE_TYPES.add(StateTypes.MOVING_PISTON);
        IMMOVABLE_TYPES.add(StateTypes.END_PORTAL);
        IMMOVABLE_TYPES.add(StateTypes.NETHER_PORTAL);
        IMMOVABLE_TYPES.add(StateTypes.END_GATEWAY);
        IMMOVABLE_TYPES.add(StateTypes.CHEST);
        IMMOVABLE_TYPES.add(StateTypes.TRAPPED_CHEST);
        IMMOVABLE_TYPES.add(StateTypes.ENDER_CHEST);
        IMMOVABLE_TYPES.add(StateTypes.FURNACE);
        IMMOVABLE_TYPES.add(StateTypes.BLAST_FURNACE);
        IMMOVABLE_TYPES.add(StateTypes.SMOKER);
        IMMOVABLE_TYPES.add(StateTypes.BARREL);
        IMMOVABLE_TYPES.add(StateTypes.DISPENSER);
        IMMOVABLE_TYPES.add(StateTypes.DROPPER);
        IMMOVABLE_TYPES.add(StateTypes.HOPPER);
        IMMOVABLE_TYPES.add(StateTypes.BREWING_STAND);
        IMMOVABLE_TYPES.add(StateTypes.BEACON);
        IMMOVABLE_TYPES.add(StateTypes.ENCHANTING_TABLE);
        IMMOVABLE_TYPES.add(StateTypes.DAYLIGHT_DETECTOR);
        IMMOVABLE_TYPES.add(StateTypes.JUKEBOX);
        IMMOVABLE_TYPES.add(StateTypes.BELL);
        IMMOVABLE_TYPES.add(StateTypes.CAMPFIRE);
        IMMOVABLE_TYPES.add(StateTypes.SOUL_CAMPFIRE);
        IMMOVABLE_TYPES.add(StateTypes.LODESTONE);
    }

    public PistonEvent() {
        super(PacketListenerPriority.HIGH);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    private static boolean isCloseEnough(Vector3i vectorA, Vector3d vectorB) {
        return Math.abs(vectorA.getX() - vectorB.getX()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs(vectorA.getY() - vectorB.getY()) <= MAX_VERTICAL_DISTANCE
                && Math.abs(vectorA.getZ() - vectorB.getZ()) <= MAX_HORIZONTAL_DISTANCE;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.BLOCK_ACTION) {
            return;
        }

        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) return;

        WrapperPlayServerBlockAction blockAction = new WrapperPlayServerBlockAction(event);
        Vector3i blockPos = blockAction.getBlockPosition();

        if (player.compensatedEntities.self == null || player.compensatedEntities.self.trackedServerPosition == null) {
            return;
        }

        Vector3d playerPos = player.compensatedEntities.self.trackedServerPosition.getPos();
        if (!isCloseEnough(blockPos, playerPos)) {
            return;
        }

        int chunkX = blockPos.getX() >> 4;
        int chunkZ = blockPos.getZ() >> 4;
        if (!player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        int lastTrans = player.lastTransactionSent.get();
        player.latencyUtils.addRealTimeTaskAsync(lastTrans, () -> {
            WrappedBlockState state = player.compensatedWorld.getBlock(blockPos);
            StateType type = state.getType();
            if (type != StateTypes.PISTON && type != StateTypes.STICKY_PISTON) {
                return;
            }

            BlockFace direction = BlockFace.getBlockFaceByValue(blockAction.getActionData());
            if (direction == null || direction == BlockFace.OTHER) {
                direction = state.getFacing();
            }
            if (direction == null) {
                direction = BlockFace.UP;
            }

            int actionId = blockAction.getActionId();
            boolean extending = (actionId == 1) || (actionId != 0 && actionId != 2 && !state.isExtended());

            PistonData data = resolvePiston(player.compensatedWorld, blockPos, direction, extending, type == StateTypes.STICKY_PISTON, lastTrans);
            player.compensatedWorld.activePistons.add(data);
        });
    }

    private static PistonData resolvePiston(CompensatedWorld world, Vector3i pistonPos, BlockFace direction, boolean extending, boolean isSticky, int lastTrans) {
        int px = pistonPos.getX();
        int py = pistonPos.getY();
        int pz = pistonPos.getZ();

        int dx = direction.getModX();
        int dy = direction.getModY();
        int dz = direction.getModZ();

        List<SimpleCollisionBox> boxes = new ArrayList<>();

        int hx = px + dx;
        int hy = py + dy;
        int hz = pz + dz;

        int pdx = extending ? dx : -dx;
        int pdy = extending ? dy : -dy;
        int pdz = extending ? dz : -dz;

        Vector3i startPos = extending ? new Vector3i(hx, hy, hz) : (isSticky ? new Vector3i(hx + dx, hy + dy, hz + dz) : null);

        List<Vector3i> toPush = new ArrayList<>();
        boolean hasSlime = false;
        boolean hasHoney = false;

        if (startPos != null) {
            WrappedBlockState startState = world.getBlock(startPos);
            if (startState != null && !startState.getType().isAir() && !isDestroyable(startState)) {
                if (!isImmovable(startState)) {
                    List<Vector3i> resolved = resolveStructure(world, startPos, pistonPos, pdx, pdy, pdz);
                    if (resolved != null) {
                        toPush = resolved;
                    }
                }
            }
        }

        for (Vector3i pos : toPush) {
            int bx = pos.getX();
            int by = pos.getY();
            int bz = pos.getZ();

            boxes.add(new SimpleCollisionBox(bx, by, bz, bx + 1, by + 1, bz + 1, true));
            boxes.add(new SimpleCollisionBox(bx + pdx, by + pdy, bz + pdz, bx + pdx + 1, by + pdy + 1, bz + pdz + 1, true));

            WrappedBlockState bs = world.getBlock(pos);
            if (bs != null) {
                if (bs.getType() == StateTypes.SLIME_BLOCK) hasSlime = true;
                if (bs.getType() == StateTypes.HONEY_BLOCK) hasHoney = true;
            }
        }

        if (extending || toPush.isEmpty()) {
            boxes.add(new SimpleCollisionBox(hx, hy, hz, hx + 1, hy + 1, hz + 1, true));
        }

        return new PistonData(direction, boxes, lastTrans, extending, hasSlime, hasHoney);
    }

    private static List<Vector3i> resolveStructure(CompensatedWorld world, Vector3i startPos, Vector3i pistonPos, int pdx, int pdy, int pdz) {
        List<Vector3i> toPush = new ArrayList<>(MAX_PUSH_BLOCKS);
        Set<Vector3i> visited = new HashSet<>(16);
        ArrayDeque<Vector3i> queue = new ArrayDeque<>(16);

        toPush.add(startPos);
        visited.add(startPos);
        queue.add(startPos);

        while (!queue.isEmpty()) {
            Vector3i curr = queue.poll();
            WrappedBlockState currState = world.getBlock(curr);
            if (currState == null) continue;

            // Block directly in the push direction
            Vector3i forward = curr.add(pdx, pdy, pdz);
            if (!visited.contains(forward) && !forward.equals(pistonPos)) {
                WrappedBlockState forwardState = world.getBlock(forward);
                if (forwardState != null && !forwardState.getType().isAir() && !isDestroyable(forwardState)) {
                    if (isImmovable(forwardState)) {
                        return null;
                    }
                    if (toPush.size() >= MAX_PUSH_BLOCKS) {
                        return null;
                    }
                    visited.add(forward);
                    toPush.add(forward);
                    queue.add(forward);
                }
            }

            if (isStickyBlock(currState.getType())) {
                for (BlockFace face : ALL_FACES) {
                    Vector3i adj = curr.add(face.getModX(), face.getModY(), face.getModZ());
                    if (visited.contains(adj) || adj.equals(pistonPos)) {
                        continue;
                    }

                    WrappedBlockState adjState = world.getBlock(adj);
                    if (adjState == null || adjState.getType().isAir() || isDestroyable(adjState)) {
                        continue;
                    }

                    if (!canStickTogether(currState.getType(), adjState.getType())) {
                        continue;
                    }

                    if (isImmovable(adjState)) {
                        return null;
                    }

                    if (toPush.size() >= MAX_PUSH_BLOCKS) {
                        return null;
                    }

                    visited.add(adj);
                    toPush.add(adj);
                    queue.add(adj);
                }
            }
        }

        return toPush;
    }

    private static boolean isDestroyable(WrappedBlockState state) {
        StateType type = state.getType();
        if (type.isAir()) return true;
        if (type == StateTypes.WATER || type == StateTypes.LAVA) return true;
        return type.getHardness() == 0.0F && type != StateTypes.MOVING_PISTON;
    }

    private static boolean isImmovable(WrappedBlockState state) {
        StateType type = state.getType();
        if (type.getHardness() == -1.0F) return true;
        if (IMMOVABLE_TYPES.contains(type)) return true;
        if ((type == StateTypes.PISTON || type == StateTypes.STICKY_PISTON) && state.isExtended()) return true;
        return Materials.isShulker(type);
    }

    private static boolean isStickyBlock(StateType type) {
        return type == StateTypes.SLIME_BLOCK || type == StateTypes.HONEY_BLOCK;
    }

    private static boolean canStickTogether(StateType a, StateType b) {
        if (a == StateTypes.HONEY_BLOCK && b == StateTypes.SLIME_BLOCK) return false;
        return !(a == StateTypes.SLIME_BLOCK && b == StateTypes.HONEY_BLOCK);
    }
}
