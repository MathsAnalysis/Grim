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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@CheckData(name = "AutoArmor", stableKey = "grim.combat.autoarmor", description = "Detects unfair automated armor equipping", experimental = false)
public class AutoArmor extends Check implements PacketReceiveListener, PacketSendListener {
    private static final Verbose V_CONCURRENT = Verbose.of("action={str}");
    private static final Verbose V_ROTATION = Verbose.of("deltaRot={f64:%.2f}");
    private static final Verbose V_SPEED = Verbose.of("distinctTypes={uint}, duration={ulong}ms");

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
        if (player.disableGrim) return;
        if (player.gamemode == GameMode.SPECTATOR) return;

        final PacketTypeCommon packetType = event.getPacketType();
        final long now = System.currentTimeMillis();

        if (packetType == PacketType.Play.Client.PLAYER_ROTATION
                || packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            float yaw = flying.getLocation().getYaw();
            float pitch = flying.getLocation().getPitch();

            if (initializedLook && !player.inVehicle() && !player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(3)) {
                float deltaYaw = Math.abs(yaw - lastYaw);
                float deltaPitch = Math.abs(pitch - lastPitch);
                if (deltaYaw > 3.0f || deltaPitch > 3.0f) {
                    lastSignificantRotationTime = now;
                    lastRotationTick = tick;
                    lastDeltaRot = Math.max(deltaYaw, deltaPitch);
                }
            }
            lastYaw = yaw;
            lastPitch = pitch;
            initializedLook = true;
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
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = digging.getAction();
            if (action == DiggingAction.START_DIGGING) {
                isMining = true;
            } else if (action == DiggingAction.CANCELLED_DIGGING || action == DiggingAction.FINISHED_DIGGING) {
                isMining = false;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);

            EquipmentType targetType = getTargetArmorType(click);
            if (targetType == null) {
                lastSlotClicked = click.getSlot();
                return;
            }

            handleArmorEquip(event, targetType, click.getSlot(), true);
            lastSlotClicked = click.getSlot();
            return;
        }

        if (packetType == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem useItem = new WrapperPlayClientUseItem(event);
            ItemStack held = useItem.getHand() == InteractionHand.MAIN_HAND
                    ? player.inventory.getHeldItem()
                    : player.inventory.getOffHand();
            if (held == null || held.isEmpty() || held.getType() == ItemTypes.AIR) {
                if (player.platformPlayer != null) {
                    try {
                        held = useItem.getHand() == InteractionHand.MAIN_HAND
                                ? player.platformPlayer.getInventory().getItemInHand()
                                : player.platformPlayer.getInventory().getItemInOffHand();
                    } catch (Exception ignored) {}
                }
            }
            if (held != null && !held.isEmpty() && held.getType() != ItemTypes.AIR) {
                EquipmentType eq = EquipmentType.getEquipmentSlotForItem(held);
                if (eq != null && eq.isArmor()) {
                    int slot = useItem.getHand() == InteractionHand.MAIN_HAND
                            ? Inventory.HOTBAR_OFFSET + player.inventory.inventory.getSelected()
                            : Inventory.SLOT_OFFHAND;
                    handleArmorEquip(event, eq, slot, false);
                    lastSlotClicked = slot;
                }
            }
        }
    }

    private void handleArmorEquip(PacketReceiveEvent event, EquipmentType targetType, int slot, boolean isWindowClick) {
        final long now = System.currentTimeMillis();

        recentEquips.removeIf(e -> (now - e.timestamp) > 1200L);

        if (!recentEquips.isEmpty()) {
            EquipRecord last = recentEquips.get(recentEquips.size() - 1);
            if (slot == lastSlotClicked || last.type() == targetType) {
                return;
            }
        }

        if (isWindowClick) {
            if ((now - lastAttackTime) <= 100L) {
                flagAndCancel(event, V_CONCURRENT.write(verbose()).str("attack"));
                return;
            }

            if (isMining) {
                flagAndCancel(event, V_CONCURRENT.write(verbose()).str("digging"));
                return;
            }

            if ((tick == lastRotationTick || (now - lastSignificantRotationTime) <= 50L)
                    && !player.inVehicle() && !player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(3)) {
                rotBuffer += 1.0;
                if (rotBuffer >= 3.0) {
                    flagAndCancel(event, V_ROTATION.write(verbose()).f64(lastDeltaRot));
                    return;
                }
            } else {
                rotBuffer = Math.max(0, rotBuffer - 0.25);
            }
        } else {
            rotBuffer = Math.max(0, rotBuffer - 0.25);
        }

        final boolean isLaggingOrBurst = player.canSkipTicks() || !player.isTickingReliablyFor(3);

        if (!recentEquips.isEmpty()) {
            EquipRecord prev = recentEquips.get(recentEquips.size() - 1);
            if (prev.type() != targetType) {
                long delay = now - prev.timestamp();
                if (!isLaggingOrBurst && (delay <= 25L || (prev.tick() == tick && isWindowClick))) {
                    fastPairBuffer += 1.0;
                    if (fastPairBuffer >= 3.0) {
                        flagAndCancel(event, V_SPEED.write(verbose()).uint(2).ulong(delay));
                        return;
                    }
                } else {
                    fastPairBuffer = Math.max(0, fastPairBuffer - 0.25);
                }
            }
        }

        recentEquips.add(new EquipRecord(targetType, slot, now, tick));

        if (!isLaggingOrBurst) {
            Set<EquipmentType> distinctThisTick = EnumSet.noneOf(EquipmentType.class);
            for (EquipRecord record : recentEquips) {
                if (record.tick() == tick || (now - record.timestamp) <= 50L) {
                    distinctThisTick.add(record.type());
                }
            }
            if (distinctThisTick.size() >= 3) {
                flagAndCancel(event, V_SPEED.write(verbose()).uint(distinctThisTick.size()).ulong(0L));
                return;
            }

            Set<EquipmentType> distinctPieces = EnumSet.noneOf(EquipmentType.class);
            for (EquipRecord record : recentEquips) {
                distinctPieces.add(record.type());
            }

            if (distinctPieces.size() >= 4 && recentEquips.size() >= 4) {
                long fourPieceDuration = now - recentEquips.get(recentEquips.size() - 4).timestamp();
                if (fourPieceDuration < 160L) {
                    flagAndCancel(event, V_SPEED.write(verbose()).uint(4).ulong(fourPieceDuration));
                    return;
                }
            }

            if (distinctPieces.size() >= 3 && recentEquips.size() >= 3) {
                long threePieceDuration = now - recentEquips.get(recentEquips.size() - 3).timestamp();
                if (threePieceDuration < 100L) {
                    flagAndCancel(event, V_SPEED.write(verbose()).uint(3).ulong(threePieceDuration));
                }
            }
        }
    }

    private EquipmentType getTargetArmorType(WrapperPlayClientClickWindow click) {
        if (click.getWindowId() != 0) return null;

        int slot = click.getSlot();

        if (click.getWindowClickType() == WrapperPlayClientClickWindow.WindowClickType.SWAP) {
            if (slot >= 5 && slot <= 8) {
                return getArmorTypeForSlot(slot);
            }
            return null;
        }

        if (click.getWindowClickType() == WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE) {
            if (slot >= 5 && slot <= 8) {
                return null;
            }

            ItemStack inSlot = null;
            if (slot >= 0 && slot < player.inventory.menu.getSlots().size()) {
                inSlot = player.inventory.menu.getSlot(slot).getItem();
            }
            if ((inSlot == null || inSlot.isEmpty() || inSlot.getType() == ItemTypes.AIR) && player.platformPlayer != null) {
                int bukkitSlot = CorrectingPlayerInventoryStorage.getBukkitSlot(slot);
                if (bukkitSlot != -1) {
                    try {
                        inSlot = player.platformPlayer.getInventory().getStack(bukkitSlot, slot);
                    } catch (Exception ignored) {}
                }
            }
            if (inSlot != null && !inSlot.isEmpty() && inSlot.getType() != ItemTypes.AIR) {
                EquipmentType type = EquipmentType.getEquipmentSlotForItem(inSlot);
                if (type.isArmor()) {
                    return type;
                }
            }
            return null;
        }

        ItemStack carried = click.getCarriedItemStack();
        if (carried != null && !carried.isEmpty() && carried.getType() != ItemTypes.AIR) {
            EquipmentType type = EquipmentType.getEquipmentSlotForItem(carried);
            if (type.isArmor() && slot >= 5 && slot <= 8) {
                return type;
            }
        }

        return null;
    }

    private static EquipmentType getArmorTypeForSlot(int slot) {
        return switch (slot) {
            case 5 -> EquipmentType.HEAD;
            case 6 -> EquipmentType.CHEST;
            case 7 -> EquipmentType.LEGS;
            case 8 -> EquipmentType.FEET;
            default -> null;
        };
    }

    private void flagAndCancel(PacketReceiveEvent event, Verbose.Writer verbose) {
        if (flag(verbose) && shouldModifyPackets()) {
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
