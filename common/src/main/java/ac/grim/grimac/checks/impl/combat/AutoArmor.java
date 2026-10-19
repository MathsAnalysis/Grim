package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.EquipmentType;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.lists.CorrectingPlayerInventoryStorage;
import ac.grim.grimac.utils.math.Location;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeathCombatEvent;

import java.util.ArrayList;
import java.util.List;

@CheckData(
        name = "AutoArmor",
        stableKey = "grim.combat.autoarmor",
        description = "Detects unfair automated armor equipping",
        experimental = false
)
public class AutoArmor extends Check implements PacketReceiveListener, PacketSendListener {

    private static final Verbose V_CONCURRENT = Verbose.of("action={str}");
    private static final Verbose V_ROTATION = Verbose.of("deltaRot={f64:%.2f}");
    private static final Verbose V_SPEED = Verbose.of("distinctTypes={uint}, duration={ulong}ms");

    private static final int INVENTORY_WINDOW_ID = 0;
    private static final int ARMOR_SLOT_HEAD = 5;
    private static final int ARMOR_SLOT_CHEST = 6;
    private static final int ARMOR_SLOT_LEGS = 7;
    private static final int ARMOR_SLOT_FEET = 8;
    private static final int ARMOR_SLOT_MIN = ARMOR_SLOT_HEAD;
    private static final int ARMOR_SLOT_MAX = ARMOR_SLOT_FEET;

    private static final long HISTORY_EXPIRATION_MS = 1200L;
    private static final long CONCURRENT_ATTACK_WINDOW_MS = 100L;
    private static final long CONCURRENT_ROTATION_WINDOW_MS = 50L;
    private static final float MIN_SIGNIFICANT_ROTATION_DEGREES = 3.0f;
    private static final int TELEPORT_GRACE_TICKS = 3;

    private static final double ROTATION_VIOLATION_THRESHOLD = 3.0;
    private static final double ROTATION_BUFFER_INCREMENT = 1.0;
    private static final double FAST_PAIR_VIOLATION_THRESHOLD = 3.0;
    private static final double FAST_PAIR_BUFFER_INCREMENT = 1.0;
    private static final double BUFFER_DECAY = 0.25;

    private static final long FAST_PAIR_MAX_DELAY_MS = 25L;
    private static final long SAME_TICK_BURST_WINDOW_MS = 50L;
    private static final long THREE_PIECE_BURST_MAX_MS = 100L;
    private static final long FOUR_PIECE_BURST_MAX_MS = 160L;
    private static final int MIN_BURST_SAME_TICK_PIECES = 3;

    private static final PacketTypeCommon[] RECEIVE_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.CLIENT_TICK_END,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.PLAYER_DIGGING,
            PacketType.Play.Client.CLICK_WINDOW,
            PacketType.Play.Client.USE_ITEM
    };

    private static final PacketTypeCommon[] SEND_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Server.RESPAWN,
            PacketType.Play.Server.JOIN_GAME,
            PacketType.Play.Server.DEATH_COMBAT_EVENT
    };

    private record EquipRecord(EquipmentType type, int slot, long timestamp, int tick) {}

    private int tick;
    private long lastAttackTime;
    private boolean isMining;

    private boolean initializedLook;
    private float lastYaw;
    private float lastPitch;
    private long lastSignificantRotationTime;
    private int lastRotationTick = -1;
    private double lastDeltaRot;

    private double rotBuffer;
    private double fastPairBuffer;

    private int lastSlotClicked = -1;
    private final List<EquipRecord> recentEquips = new ArrayList<>();

    public AutoArmor(GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return RECEIVE_PACKETS;
    }

    @Override
    public PacketTypeCommon[] getHandledSendPacketTypes() {
        return SEND_PACKETS;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Server.RESPAWN || packetType == PacketType.Play.Server.JOIN_GAME) {
            resetTracking();
            return;
        }

        if (packetType == PacketType.Play.Server.DEATH_COMBAT_EVENT
                && new WrapperPlayServerDeathCombatEvent(event).getPlayerId() == player.entityID) {
            resetTracking();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim || player.gamemode == GameMode.SPECTATOR) {
            return;
        }

        final PacketTypeCommon packetType = event.getPacketType();
        final long now = System.currentTimeMillis();

        if (packetType == PacketType.Play.Client.PLAYER_ROTATION
                || packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            handlePlayerRotation(event, now);
        }

        if (isTickPacket(packetType)) {
            tick++;
            return;
        }

        if (packetType == PacketType.Play.Client.ATTACK) {
            lastAttackTime = now;
            return;
        }

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttackTime = now;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_DIGGING) {
            handlePlayerDigging(event);
            return;
        }

        if (packetType == PacketType.Play.Client.CLICK_WINDOW) {
            handleWindowClick(event);
            return;
        }

        if (packetType == PacketType.Play.Client.USE_ITEM) {
            handleItemUse(event);
        }
    }

    private void handlePlayerRotation(PacketReceiveEvent event, long now) {
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        float yaw = flying.getLocation().getYaw();
        float pitch = flying.getLocation().getPitch();

        if (initializedLook && canTrackRotation()) {
            float deltaYaw = Math.abs(Location.normalizeYaw(yaw - lastYaw));
            float deltaPitch = Math.abs(pitch - lastPitch);
            if (deltaYaw > MIN_SIGNIFICANT_ROTATION_DEGREES || deltaPitch > MIN_SIGNIFICANT_ROTATION_DEGREES) {
                lastSignificantRotationTime = now;
                lastRotationTick = tick;
                lastDeltaRot = Math.max(deltaYaw, deltaPitch);
            }
        }

        lastYaw = yaw;
        lastPitch = pitch;
        initializedLook = true;
    }

    private boolean canTrackRotation() {
        return !player.inVehicle() && !player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(TELEPORT_GRACE_TICKS);
    }

    private void handlePlayerDigging(PacketReceiveEvent event) {
        WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = digging.getAction();
        if (action == DiggingAction.START_DIGGING) {
            isMining = true;
        } else if (action == DiggingAction.CANCELLED_DIGGING || action == DiggingAction.FINISHED_DIGGING) {
            isMining = false;
        }
    }

    private void handleWindowClick(PacketReceiveEvent event) {
        WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
        EquipmentType targetType = resolveArmorTypeFromClick(click);

        if (targetType != null) {
            handleArmorEquip(event, targetType, click.getSlot(), true);
        }
        lastSlotClicked = click.getSlot();
    }

    private void handleItemUse(PacketReceiveEvent event) {
        WrapperPlayClientUseItem useItem = new WrapperPlayClientUseItem(event);
        ItemStack held = getHeldItemStack(useItem.getHand());

        if (held == null || held.isEmpty() || held.getType() == ItemTypes.AIR) {
            return;
        }

        EquipmentType equipmentType = EquipmentType.getEquipmentSlotForItem(held);
        if (equipmentType == null || !equipmentType.isArmor()) {
            return;
        }

        int slot = useItem.getHand() == InteractionHand.MAIN_HAND
                ? Inventory.HOTBAR_OFFSET + player.inventory.inventory.getSelected()
                : Inventory.SLOT_OFFHAND;

        handleArmorEquip(event, equipmentType, slot, false);
        lastSlotClicked = slot;
    }

    private ItemStack getHeldItemStack(InteractionHand hand) {
        ItemStack held = hand == InteractionHand.MAIN_HAND
                ? player.inventory.getHeldItem()
                : player.inventory.getOffHand();

        if ((held == null || held.isEmpty() || held.getType() == ItemTypes.AIR) && player.platformPlayer != null) {
            try {
                held = hand == InteractionHand.MAIN_HAND
                        ? player.platformPlayer.getInventory().getItemInHand()
                        : player.platformPlayer.getInventory().getItemInOffHand();
            } catch (Exception ignored) {}
        }
        return held;
    }

    private void handleArmorEquip(PacketReceiveEvent event, EquipmentType targetType, int slot, boolean isWindowClick) {
        final long now = System.currentTimeMillis();
        pruneExpiredHistory(now);

        if (isDuplicateEquip(slot, targetType)) {
            return;
        }

        if (isWindowClick) {
            if (checkConcurrentCombatActions(event, now)) return;
            if (checkConcurrentRotation(event, now)) return;
        } else {
            rotBuffer = Math.max(0.0, rotBuffer - BUFFER_DECAY);
        }

        final boolean isNetworkUnstable = player.canSkipTicks() || !player.isTickingReliablyFor(3);

        if (checkFastPairEquip(event, targetType, now, isWindowClick, isNetworkUnstable)) {
            return;
        }

        recentEquips.add(new EquipRecord(targetType, slot, now, tick));

        if (!isNetworkUnstable) {
            checkEquipSpeed(event, now);
        }
    }

    private void pruneExpiredHistory(long now) {
        recentEquips.removeIf(e -> (now - e.timestamp) > HISTORY_EXPIRATION_MS);
    }

    private boolean isDuplicateEquip(int slot, EquipmentType targetType) {
        if (recentEquips.isEmpty()) {
            return false;
        }
        EquipRecord last = recentEquips.get(recentEquips.size() - 1);
        return slot == lastSlotClicked || last.type() == targetType;
    }

    private boolean checkConcurrentCombatActions(PacketReceiveEvent event, long now) {
        if ((now - lastAttackTime) <= CONCURRENT_ATTACK_WINDOW_MS) {
            flagAndCancel(event, V_CONCURRENT.write(verbose()).str("attack"));
            return true;
        }

        if (isMining) {
            flagAndCancel(event, V_CONCURRENT.write(verbose()).str("digging"));
            return true;
        }

        return false;
    }

    private boolean checkConcurrentRotation(PacketReceiveEvent event, long now) {
        boolean recentSignificantRotation = tick == lastRotationTick
                || (now - lastSignificantRotationTime) <= CONCURRENT_ROTATION_WINDOW_MS;

        if (recentSignificantRotation && canTrackRotation()) {
            rotBuffer += ROTATION_BUFFER_INCREMENT;
            if (rotBuffer >= ROTATION_VIOLATION_THRESHOLD) {
                flagAndCancel(event, V_ROTATION.write(verbose()).f64(lastDeltaRot));
                return true;
            }
        } else {
            rotBuffer = Math.max(0.0, rotBuffer - BUFFER_DECAY);
        }
        return false;
    }

    private boolean checkFastPairEquip(PacketReceiveEvent event, EquipmentType targetType, long now,
                                       boolean isWindowClick, boolean isNetworkUnstable) {
        if (recentEquips.isEmpty()) {
            return false;
        }

        EquipRecord prev = recentEquips.get(recentEquips.size() - 1);
        if (prev.type() == targetType) {
            return false;
        }

        long delay = now - prev.timestamp();
        boolean tooFast = delay <= FAST_PAIR_MAX_DELAY_MS || (prev.tick() == tick && isWindowClick);

        if (!isNetworkUnstable && tooFast) {
            fastPairBuffer += FAST_PAIR_BUFFER_INCREMENT;
            if (fastPairBuffer >= FAST_PAIR_VIOLATION_THRESHOLD) {
                flagAndCancel(event, V_SPEED.write(verbose()).uint(2).ulong(delay));
                return true;
            }
        } else {
            fastPairBuffer = Math.max(0.0, fastPairBuffer - BUFFER_DECAY);
        }
        return false;
    }

    private void checkEquipSpeed(PacketReceiveEvent event, long now) {
        int sameTickMask = 0;
        int allPiecesMask = 0;

        for (EquipRecord record : recentEquips) {
            int bit = 1 << record.type().ordinal();
            allPiecesMask |= bit;
            if (record.tick() == tick || (now - record.timestamp) <= SAME_TICK_BURST_WINDOW_MS) {
                sameTickMask |= bit;
            }
        }

        int distinctSameTick = Integer.bitCount(sameTickMask);
        if (distinctSameTick >= MIN_BURST_SAME_TICK_PIECES) {
            flagAndCancel(event, V_SPEED.write(verbose()).uint(distinctSameTick).ulong(0L));
            return;
        }

        int totalDistinct = Integer.bitCount(allPiecesMask);
        if (totalDistinct >= 4 && recentEquips.size() >= 4) {
            long duration = now - recentEquips.get(recentEquips.size() - 4).timestamp();
            if (duration < FOUR_PIECE_BURST_MAX_MS) {
                flagAndCancel(event, V_SPEED.write(verbose()).uint(4).ulong(duration));
                return;
            }
        }

        if (totalDistinct >= 3 && recentEquips.size() >= 3) {
            long duration = now - recentEquips.get(recentEquips.size() - 3).timestamp();
            if (duration < THREE_PIECE_BURST_MAX_MS) {
                flagAndCancel(event, V_SPEED.write(verbose()).uint(3).ulong(duration));
            }
        }
    }

    private EquipmentType resolveArmorTypeFromClick(WrapperPlayClientClickWindow click) {
        if (click.getWindowId() != INVENTORY_WINDOW_ID) {
            return null;
        }

        return switch (click.getWindowClickType()) {
            case SWAP -> resolveSwapClick(click);
            case QUICK_MOVE -> resolveQuickMoveClick(click);
            default -> resolvePickupClick(click);
        };
    }

    private EquipmentType resolveSwapClick(WrapperPlayClientClickWindow click) {
        int slot = click.getSlot();
        return isArmorSlot(slot) ? getArmorTypeForSlot(slot) : null;
    }

    private EquipmentType resolveQuickMoveClick(WrapperPlayClientClickWindow click) {
        int slot = click.getSlot();
        if (isArmorSlot(slot)) {
            return null;
        }

        ItemStack itemInSlot = getItemInSlot(slot);
        if (itemInSlot != null && !itemInSlot.isEmpty() && itemInSlot.getType() != ItemTypes.AIR) {
            EquipmentType type = EquipmentType.getEquipmentSlotForItem(itemInSlot);
            if (type != null && type.isArmor()) {
                return type;
            }
        }
        return null;
    }

    private EquipmentType resolvePickupClick(WrapperPlayClientClickWindow click) {
        int slot = click.getSlot();
        ItemStack carried = click.getCarriedItemStack();
        if (carried != null && !carried.isEmpty() && carried.getType() != ItemTypes.AIR) {
            EquipmentType type = EquipmentType.getEquipmentSlotForItem(carried);
            if (type != null && type.isArmor() && isArmorSlot(slot)) {
                return type;
            }
        }
        return null;
    }

    private ItemStack getItemInSlot(int slot) {
        ItemStack item = null;
        if (slot >= 0 && slot < player.inventory.menu.getSlots().size()) {
            item = player.inventory.menu.getSlot(slot).getItem();
        }
        if ((item == null || item.isEmpty() || item.getType() == ItemTypes.AIR) && player.platformPlayer != null) {
            int bukkitSlot = CorrectingPlayerInventoryStorage.getBukkitSlot(slot);
            if (bukkitSlot != -1) {
                try {
                    item = player.platformPlayer.getInventory().getStack(bukkitSlot, slot);
                } catch (Exception ignored) {}
            }
        }
        return item;
    }

    private static boolean isArmorSlot(int slot) {
        return slot >= ARMOR_SLOT_MIN && slot <= ARMOR_SLOT_MAX;
    }

    private static EquipmentType getArmorTypeForSlot(int slot) {
        return switch (slot) {
            case ARMOR_SLOT_HEAD -> EquipmentType.HEAD;
            case ARMOR_SLOT_CHEST -> EquipmentType.CHEST;
            case ARMOR_SLOT_LEGS -> EquipmentType.LEGS;
            case ARMOR_SLOT_FEET -> EquipmentType.FEET;
            default -> null;
        };
    }

    private void flagAndCancel(PacketReceiveEvent event, Verbose.Writer verboseWriter) {
        if (flag(verboseWriter) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.inventory.needResend = true;
        }
    }

    private void resetTracking() {
        tick = 0;
        lastAttackTime = 0;
        isMining = false;
        lastSignificantRotationTime = 0;
        lastRotationTick = -1;
        lastDeltaRot = 0;
        rotBuffer = 0;
        fastPairBuffer = 0;
        lastSlotClicked = -1;
        recentEquips.clear();
    }
}
