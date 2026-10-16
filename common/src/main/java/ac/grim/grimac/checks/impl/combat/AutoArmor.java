package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.EquipmentType;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@CheckData(name = "AutoArmor", stableKey = "grim.combat.autoarmor", description = "Detects unfair automated armor equipping", experimental = false)
public class AutoArmor extends Check implements PacketReceiveListener, PacketSendListener {
    private static final Verbose V_CONCURRENT = Verbose.of("action={str}");
    private static final Verbose V_ROTATION = Verbose.of("deltaRot={f64:%.2f}");
    private static final Verbose V_SPEED = Verbose.of("distinctTypes={uint}, ticks={uint}");

    private static final int NEVER = -1_000_000;
    private static final int ATTACK_WINDOW_TICKS = 3;
    private static final int ROTATION_WINDOW_TICKS = 2;
    private static final int MINING_TIMEOUT_TICKS = 60;
    private static final int HISTORY_TICKS = 24;
    private static final float SIGNIFICANT_ROTATION = 3.0f;
    private static final double BUFFER_LIMIT = 3.0;
    private static final double BUFFER_DECAY_PER_TICK = 0.05;

    private record EquipRecord(EquipmentType type, int tick) {}

    private int tick;
    private int lastAttackTick = NEVER;
    private int miningStartTick = NEVER;

    private boolean initializedLook;
    private float lastYaw;
    private float lastPitch;
    private int lastSignificantRotationTick = NEVER;
    private double lastDeltaRot;

    private double rotBuffer;
    private double miningBuffer;
    private double pairBuffer;

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

        advanceTick();

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.PLAYER_ROTATION
                || packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            trackRotation(new WrapperPlayClientPlayerFlying(event));
            return;
        }

        if (packetType == PacketType.Play.Client.ATTACK) {
            lastAttackTick = tick;
            return;
        }

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttackTick = tick;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = digging.getAction();
            if (action == DiggingAction.START_DIGGING) {
                miningStartTick = player.gamemode == GameMode.CREATIVE ? NEVER : tick;
            } else if (action == DiggingAction.CANCELLED_DIGGING || action == DiggingAction.FINISHED_DIGGING) {
                miningStartTick = NEVER;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            int armorSlot = findEquippedArmorSlot(click);
            if (armorSlot != -1) {
                handleArmorEquip(getArmorTypeForSlot(armorSlot), true);
            }
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
                if (eq.isArmor()) {
                    handleArmorEquip(eq, false);
                }
            }
        }
    }

    private void advanceTick() {
        int now = GrimAPI.INSTANCE.getTickManager().currentTick;
        int elapsed = now - tick;
        if (elapsed > 0) {
            double decay = elapsed * BUFFER_DECAY_PER_TICK;
            rotBuffer = Math.max(0.0, rotBuffer - decay);
            miningBuffer = Math.max(0.0, miningBuffer - decay);
            pairBuffer = Math.max(0.0, pairBuffer - decay);
        }
        tick = now;
    }

    private void trackRotation(WrapperPlayClientPlayerFlying flying) {
        float yaw = flying.getLocation().getYaw();
        float pitch = flying.getLocation().getPitch();

        if (initializedLook && !player.inVehicle() && !player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(3)) {
            float deltaYaw = Math.abs(Location.normalizeYaw(yaw - lastYaw));
            float deltaPitch = Math.abs(pitch - lastPitch);
            if (deltaYaw > SIGNIFICANT_ROTATION || deltaPitch > SIGNIFICANT_ROTATION) {
                lastSignificantRotationTick = tick;
                lastDeltaRot = Math.max(deltaYaw, deltaPitch);
            }
        }
        lastYaw = yaw;
        lastPitch = pitch;
        initializedLook = true;
    }

    private void handleArmorEquip(EquipmentType targetType, boolean inventory) {
        recentEquips.removeIf(record -> tick - record.tick() > HISTORY_TICKS);

        if (!recentEquips.isEmpty() && recentEquips.get(recentEquips.size() - 1).type() == targetType) {
            return;
        }

        if (inventory) {
            if (tick - lastAttackTick <= ATTACK_WINDOW_TICKS) {
                flag(V_CONCURRENT.write(verbose()).str("attack"));
                return;
            }

            if (tick - miningStartTick <= MINING_TIMEOUT_TICKS) {
                miningBuffer += 1.0;
                if (miningBuffer >= BUFFER_LIMIT) {
                    flag(V_CONCURRENT.write(verbose()).str("digging"));
                    return;
                }
            }

            if (tick - lastSignificantRotationTick <= ROTATION_WINDOW_TICKS && !player.inVehicle() && !player.uncertaintyHandler.lastTeleportTicks.hasOccurredSince(3)) {
                rotBuffer += 1.0;
                if (rotBuffer >= BUFFER_LIMIT) {
                    flag(V_ROTATION.write(verbose()).f64(lastDeltaRot));
                    return;
                }
            }
        }

        if (!recentEquips.isEmpty() && recentEquips.get(recentEquips.size() - 1).tick() == tick) {
            pairBuffer += 1.0;
            if (pairBuffer >= BUFFER_LIMIT) {
                flag(V_SPEED.write(verbose()).uint(2).uint(1));
                return;
            }
        }

        int typesThisTick = distinctWithin(0, targetType);
        if (typesThisTick >= 3) {
            flag(V_SPEED.write(verbose()).uint(typesThisTick).uint(1));
            return;
        }

        int typesWithinTwoTicks = distinctWithin(1, targetType);
        if (typesWithinTwoTicks >= 3) {
            flag(V_SPEED.write(verbose()).uint(typesWithinTwoTicks).uint(2));
            return;
        }

        int typesWithinFourTicks = distinctWithin(3, targetType);
        if (typesWithinFourTicks >= 4) {
            flag(V_SPEED.write(verbose()).uint(typesWithinFourTicks).uint(4));
            return;
        }

        recentEquips.add(new EquipRecord(targetType, tick));
    }

    private int distinctWithin(int ticks, EquipmentType current) {
        EnumSet<EquipmentType> types = EnumSet.of(current);
        for (EquipRecord record : recentEquips) {
            if (tick - record.tick() <= ticks) {
                types.add(record.type());
            }
        }
        return types.size();
    }

    private static int findEquippedArmorSlot(WrapperPlayClientClickWindow click) {
        if (click.getWindowId() != 0) return -1;

        Optional<Map<Integer, ItemStack>> changed = click.getSlots();
        if (changed.isEmpty()) return -1;

        Map<Integer, ItemStack> slots = changed.get();
        for (int slot = 5; slot <= 8; slot++) {
            ItemStack stack = slots.get(slot);
            if (stack == null || stack.isEmpty() || stack.getType() == ItemTypes.AIR) continue;
            if (EquipmentType.getEquipmentSlotForItem(stack) == getArmorTypeForSlot(slot)) {
                return slot;
            }
        }
        return -1;
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

    private void resetTracking() {
        lastAttackTick = NEVER;
        miningStartTick = NEVER;
        initializedLook = false;
        lastSignificantRotationTick = NEVER;
        lastDeltaRot = 0;
        rotBuffer = 0;
        miningBuffer = 0;
        pairBuffer = 0;
        recentEquips.clear();
    }
}
