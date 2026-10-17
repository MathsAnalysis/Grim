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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class PistonEvent extends PacketListenerAbstract {

    private static final double MAX_HORIZONTAL_DISTANCE = 24.0;
    private static final double MAX_VERTICAL_DISTANCE = 64.0;
    private static final int MAX_PUSH_BLOCKS = 12;

    private static final int PUSH_BLOCKED = -1;
    private static final int INITIAL_PUSH_QUEUE_SIZE = 1;

    private static final int[] ADJACENT_DX = {0, 0, 0, 0, -1, 1};
    private static final int[] ADJACENT_DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] ADJACENT_DZ = {0, 0, -1, 1, 0, 0};

    private static final int PACKED_X_BITS = 26;
    private static final int PACKED_Z_BITS = 26;
    private static final int PACKED_Y_BITS = 12;
    private static final long PACKED_XZ_MASK = (1L << PACKED_X_BITS) - 1L;
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_BITS) - 1L;
    private static final int PACKED_Y_SHIFT = PACKED_Z_BITS;
    private static final int PACKED_X_SHIFT = PACKED_Z_BITS + PACKED_Y_BITS;

    private static final ThreadLocal<long[]> PUSH_BUFFER = ThreadLocal.withInitial(() -> new long[MAX_PUSH_BLOCKS]);
    private static final Set<StateType> IMMOVABLE_TYPES = Collections.newSetFromMap(new IdentityHashMap<>(64));

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

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.BLOCK_ACTION) {
            return;
        }

        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null || !hasTrackedPosition(player)) {
            return;
        }

        WrapperPlayServerBlockAction blockAction = new WrapperPlayServerBlockAction(event);
        Vector3i blockPos = blockAction.getBlockPosition();
        int bx = blockPos.getX();
        int by = blockPos.getY();
        int bz = blockPos.getZ();

        Vector3d playerPos = player.compensatedEntities.self.trackedServerPosition.getPos();
        if (!isCloseEnough(bx, by, bz, playerPos)) {
            return;
        }

        if (!player.compensatedWorld.isChunkLoaded(bx >> 4, bz >> 4)) {
            return;
        }

        schedulePistonTask(player, blockAction, bx, by, bz);
    }

    private void schedulePistonTask(GrimPlayer player, WrapperPlayServerBlockAction blockAction, int bx, int by, int bz) {
        int actionId = blockAction.getActionId();
        int actionData = blockAction.getActionData();
        int lastTrans = player.lastTransactionSent.get();

        player.latencyUtils.addRealTimeTaskAsync(lastTrans, () -> {
            WrappedBlockState state = player.compensatedWorld.getBlock(bx, by, bz);
            StateType type = state.getType();
            if (type != StateTypes.PISTON && type != StateTypes.STICKY_PISTON) {
                return;
            }

            BlockFace direction = resolveDirection(actionData, state);
            boolean extending = isExtendingAction(actionId, state.isExtended());
            boolean isSticky = type == StateTypes.STICKY_PISTON;

            PistonData data = resolvePiston(player.compensatedWorld, bx, by, bz, direction, extending, isSticky, lastTrans);
            player.compensatedWorld.activePistons.add(data);
        });
    }

    private static PistonData resolvePiston(CompensatedWorld world, int px, int py, int pz, BlockFace direction, boolean extending, boolean isSticky, int lastTrans) {
        int dx = direction.getModX();
        int dy = direction.getModY();
        int dz = direction.getModZ();

        int headX = px + dx;
        int headY = py + dy;
        int headZ = pz + dz;

        int pushDx = extending ? dx : -dx;
        int pushDy = extending ? dy : -dy;
        int pushDz = extending ? dz : -dz;

        long[] pushBuffer = PUSH_BUFFER.get();
        int pushedCount = resolvePushedBlocks(world, px, py, pz, headX, headY, headZ, dx, dy, dz, pushDx, pushDy, pushDz, extending, isSticky, pushBuffer);

        return createPistonData(world, direction, lastTrans, extending, headX, headY, headZ, pushDx, pushDy, pushDz, pushBuffer, pushedCount);
    }

    private static int resolvePushedBlocks(CompensatedWorld world, int px, int py, int pz, int headX, int headY, int headZ, int dx, int dy, int dz, int pushDx, int pushDy, int pushDz, boolean extending, boolean isSticky, long[] buffer) {
        if (!extending && !isSticky) {
            return 0;
        }

        int startX = extending ? headX : (headX + dx);
        int startY = extending ? headY : (headY + dy);
        int startZ = extending ? headZ : (headZ + dz);

        WrappedBlockState startState = world.getBlock(startX, startY, startZ);
        if (!isMovable(startState) || isImmovable(startState)) {
            return 0;
        }

        long startPos = packPos(startX, startY, startZ);
        long pistonPos = packPos(px, py, pz);
        int resolved = resolveStructure(world, startPos, pistonPos, pushDx, pushDy, pushDz, buffer);

        return resolved > 0 ? resolved : 0;
    }

    private static int resolveStructure(CompensatedWorld world, long startPos, long pistonPos, int pdx, int pdy, int pdz, long[] toPush) {
        toPush[0] = startPos;
        int count = INITIAL_PUSH_QUEUE_SIZE;
        int head = 0;

        while (head < count) {
            long curr = toPush[head++];
            int cx = unpackX(curr);
            int cy = unpackY(curr);
            int cz = unpackZ(curr);

            WrappedBlockState currState = world.getBlock(cx, cy, cz);
            if (currState == null) {
                continue;
            }

            int forwardCount = processForwardBlock(world, cx + pdx, cy + pdy, cz + pdz, pistonPos, toPush, count);
            if (forwardCount == PUSH_BLOCKED) {
                return PUSH_BLOCKED;
            }
            count = forwardCount;

            if (isStickyBlock(currState.getType())) {
                int stickyCount = processStickyNeighbors(world, currState.getType(), cx, cy, cz, pistonPos, toPush, count);
                if (stickyCount == PUSH_BLOCKED) {
                    return PUSH_BLOCKED;
                }
                count = stickyCount;
            }
        }

        return count;
    }

    private static int processForwardBlock(CompensatedWorld world, int fx, int fy, int fz, long pistonPos, long[] toPush, int count) {
        long forward = packPos(fx, fy, fz);
        if (forward == pistonPos || contains(toPush, count, forward)) {
            return count;
        }

        WrappedBlockState state = world.getBlock(fx, fy, fz);
        if (!isMovable(state)) {
            return count;
        }

        if (isImmovable(state) || count >= MAX_PUSH_BLOCKS) {
            return PUSH_BLOCKED;
        }

        toPush[count] = forward;
        return count + 1;
    }

    private static int processStickyNeighbors(CompensatedWorld world, StateType parentType, int cx, int cy, int cz, long pistonPos, long[] toPush, int count) {
        for (int i = 0; i < 6; i++) {
            int ax = cx + ADJACENT_DX[i];
            int ay = cy + ADJACENT_DY[i];
            int az = cz + ADJACENT_DZ[i];
            long adj = packPos(ax, ay, az);

            if (adj == pistonPos || contains(toPush, count, adj)) {
                continue;
            }

            WrappedBlockState state = world.getBlock(ax, ay, az);
            if (!isMovable(state) || !canStickTogether(parentType, state.getType())) {
                continue;
            }

            if (isImmovable(state) || count >= MAX_PUSH_BLOCKS) {
                return PUSH_BLOCKED;
            }

            toPush[count++] = adj;
        }
        return count;
    }

    private static PistonData createPistonData(CompensatedWorld world, BlockFace direction, int lastTrans, boolean extending, int headX, int headY, int headZ, int pushDx, int pushDy, int pushDz, long[] pushedBlocks, int count) {
        int capacity = (count * 2) + (extending || count == 0 ? 1 : 0);
        List<SimpleCollisionBox> boxes = new ArrayList<>(capacity);

        boolean hasSlime = false;
        boolean hasHoney = false;

        for (int i = 0; i < count; i++) {
            long pos = pushedBlocks[i];
            int bx = unpackX(pos);
            int by = unpackY(pos);
            int bz = unpackZ(pos);

            boxes.add(new SimpleCollisionBox(bx, by, bz, bx + 1, by + 1, bz + 1, true));
            boxes.add(new SimpleCollisionBox(bx + pushDx, by + pushDy, bz + pushDz, bx + pushDx + 1, by + pushDy + 1, bz + pushDz + 1, true));

            WrappedBlockState state = world.getBlock(bx, by, bz);
            if (state != null) {
                StateType type = state.getType();
                if (type == StateTypes.SLIME_BLOCK) hasSlime = true;
                if (type == StateTypes.HONEY_BLOCK) hasHoney = true;
            }
        }

        if (extending || count == 0) {
            boxes.add(new SimpleCollisionBox(headX, headY, headZ, headX + 1, headY + 1, headZ + 1, true));
        }

        return new PistonData(direction, boxes, lastTrans, extending, hasSlime, hasHoney);
    }

    private static boolean hasTrackedPosition(GrimPlayer player) {
        return player.compensatedEntities.self != null
                && player.compensatedEntities.self.trackedServerPosition != null;
    }

    private static boolean isCloseEnough(int x, int y, int z, Vector3d playerPos) {
        return Math.abs(x - playerPos.getX()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs(y - playerPos.getY()) <= MAX_VERTICAL_DISTANCE
                && Math.abs(z - playerPos.getZ()) <= MAX_HORIZONTAL_DISTANCE;
    }

    private static BlockFace resolveDirection(int actionData, WrappedBlockState state) {
        BlockFace direction = BlockFace.getBlockFaceByValue(actionData);
        if (direction == null || direction == BlockFace.OTHER) {
            direction = state.getFacing();
        }
        return direction != null ? direction : BlockFace.UP;
    }

    private static boolean isExtendingAction(int actionId, boolean isExtended) {
        return actionId == 1 || (actionId != 0 && actionId != 2 && !isExtended);
    }

    private static boolean contains(long[] array, int size, long val) {
        for (int i = 0; i < size; i++) {
            if (array[i] == val) return true;
        }
        return false;
    }

    private static boolean isMovable(WrappedBlockState state) {
        return state != null && !state.getType().isAir() && !isDestroyable(state);
    }

    private static boolean isDestroyable(WrappedBlockState state) {
        StateType type = state.getType();
        if (type.isAir() || type == StateTypes.WATER || type == StateTypes.LAVA) {
            return true;
        }
        return type.getHardness() == 0.0F && type != StateTypes.MOVING_PISTON;
    }

    private static boolean isImmovable(WrappedBlockState state) {
        StateType type = state.getType();
        if (type.getHardness() == -1.0F || IMMOVABLE_TYPES.contains(type)) {
            return true;
        }
        if ((type == StateTypes.PISTON || type == StateTypes.STICKY_PISTON) && state.isExtended()) {
            return true;
        }
        return Materials.isShulker(type);
    }

    private static boolean isStickyBlock(StateType type) {
        return type == StateTypes.SLIME_BLOCK || type == StateTypes.HONEY_BLOCK;
    }

    private static boolean canStickTogether(StateType a, StateType b) {
        if (a == StateTypes.HONEY_BLOCK && b == StateTypes.SLIME_BLOCK) return false;
        return !(a == StateTypes.SLIME_BLOCK && b == StateTypes.HONEY_BLOCK);
    }

    private static long packPos(int x, int y, int z) {
        return (((long) x & PACKED_XZ_MASK) << PACKED_X_SHIFT)
                | (((long) y & PACKED_Y_MASK) << PACKED_Y_SHIFT)
                | ((long) z & PACKED_XZ_MASK);
    }

    private static int unpackX(long p) {
        return (int) (p >> PACKED_X_SHIFT);
    }

    private static int unpackY(long p) {
        return (int) ((p << 26) >> 52);
    }

    private static int unpackZ(long p) {
        return (int) ((p << PACKED_X_SHIFT) >> PACKED_X_SHIFT);
    }
}
