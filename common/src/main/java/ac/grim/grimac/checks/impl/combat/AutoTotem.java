package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.math.GrimMath;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.HashedStack;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeathCombatEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@CheckData(name = "AutoTotem", stableKey = "grim.combat.autototem", description = "Detects unfair automated Totem of Undying usage", experimental = false)
public class AutoTotem extends Check implements PacketReceiveListener, PacketSendListener {
    private static final Verbose V_REACTION = Verbose.of("source={str}, ticks={uint}");
    private static final Verbose V_CLICK_DIFF = Verbose.of("clickDiff={ulong}ms, useDiff={ulong}ms");
    private static final Verbose V_SPRINT = Verbose.of("source={str}");
    private static final Verbose V_SEQUENCE = Verbose.of("reason={str}");
    private static final Verbose V_CONSISTENCY = Verbose.of("stdDev={f64:%.2f}ms");

    private static final int TOTEM_STATUS = 35;
    private static final int FLAG_TICKS = 3;
    private static final int EXPIRE_TICKS = 40;
    private static final long MAX_CLICK_DIFF_MS = 35L;
    private static final long MAX_USE_TO_PLACE_DIFF_MS = 2500L;
    private static final int CONSISTENCY_SAMPLE_SIZE = 5;

    private int tick;
    private boolean tracking;
    private boolean confirmed;
    private int ticksSinceConfirmation;
    private int popTransaction;

    private long lastPopTime;
    private long totemPickupTimestamp;
    private boolean wasCarryingTotem;
    private int closesInTick;

    private final List<Double> recentIntervals = new ArrayList<>();

    public AutoTotem(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_11);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Server.RESPAWN || packetType == PacketType.Play.Server.JOIN_GAME) {
            resetTracking();
            return;
        }

        if (packetType == PacketType.Play.Server.DEATH_COMBAT_EVENT && new WrapperPlayServerDeathCombatEvent(event).getPlayerId() == player.entityID) {
            resetTracking();
            return;
        }

        if (packetType != PacketType.Play.Server.ENTITY_STATUS) return;

        WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
        if (status.getEntityId() != player.entityID || status.getStatus() != TOTEM_STATUS) return;

        lastPopTime = System.currentTimeMillis();
        tracking = true;
        confirmed = false;
        ticksSinceConfirmation = 0;

        event.getTasksAfterSend().add(() -> {
            player.sendTransaction();
            int transaction = player.lastTransactionSent.get();
            popTransaction = transaction;
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                if (popTransaction == transaction) {
                    confirmed = true;
                }
            });
        });
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (isTickPacket(packetType)) {
            tick++;
            closesInTick = 0;
            if (tracking && confirmed && ++ticksSinceConfirmation > EXPIRE_TICKS) {
                tracking = false;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.CLOSE_WINDOW) {
            WrapperPlayClientCloseWindow close = new WrapperPlayClientCloseWindow(event);
            if (close.getWindowId() == 0) {
                closesInTick++;
                if (closesInTick >= 2) {
                    flag(V_SEQUENCE.write(verbose()).str("multiple-close"));
                }
            }
            return;
        }

        if (packetType == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);

            if (click.getWindowId() == 0 && closesInTick > 0) {
                flag(V_SEQUENCE.write(verbose()).str("click-after-close"));
            }

            handlePossibleTotemPickup(click);

            OffhandAction action = getOffhandAction(click);
            if (action != null) {
                handleOffhandRefill("window", action);
            }
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            if (digging.getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                handleOffhandRefill("swap", new OffhandAction(true));
            }
        }
    }

    private void handlePossibleTotemPickup(WrapperPlayClientClickWindow click) {
        if (click.getWindowClickType() != WrapperPlayClientClickWindow.WindowClickType.PICKUP) return;
        if (click.getSlot() == Inventory.SLOT_OFFHAND) return;

        ItemStack carried = click.getCarriedItemStack();
        boolean nowCarrying = isDeathProtection(carried);

        if (!nowCarrying && click.getSlot() >= 0 && click.getSlot() < player.inventory.menu.getSlots().size()) {
            ItemStack inSlot = player.inventory.menu.getSlot(click.getSlot()).getItem();
            nowCarrying = isDeathProtection(inSlot);
        }

        if (nowCarrying) {
            if (!wasCarryingTotem) {
                totemPickupTimestamp = System.currentTimeMillis();
                wasCarryingTotem = true;
            }
        } else {
            wasCarryingTotem = false;
        }
    }

    private void handleOffhandRefill(String source, OffhandAction action) {
        long now = System.currentTimeMillis();

        if ("window".equals(source) && player.isSprinting && !player.isSwimming) {
            flag(V_SPRINT.write(verbose()).str("sprint-click"));
        }

        if (totemPickupTimestamp != 0) {
            long clickDiff = now - totemPickupTimestamp;
            boolean recentPop = lastPopTime != 0 && (now - lastPopTime) <= MAX_USE_TO_PLACE_DIFF_MS;
            long useDiff = recentPop ? (now - lastPopTime) : 0;
            totemPickupTimestamp = 0;
            wasCarryingTotem = false;

            if (clickDiff >= 0 && clickDiff <= MAX_CLICK_DIFF_MS) {
                flag(V_CLICK_DIFF.write(verbose()).ulong(clickDiff).ulong(useDiff));
            }
        }

        if (tracking && action.deathProtection()) {
            tracking = false;
            int elapsedTicks = confirmed ? ticksSinceConfirmation : 0;
            if (elapsedTicks <= FLAG_TICKS) {
                flag(V_REACTION.write(verbose()).str(source).uint(elapsedTicks));
            }

            if (lastPopTime != 0) {
                long interval = now - lastPopTime;
                if (interval >= 0 && interval <= 3000) {
                    addIntervalSample((double) interval);
                }
            }
        }
    }

    private void addIntervalSample(double interval) {
        recentIntervals.add(interval);
        if (recentIntervals.size() > CONSISTENCY_SAMPLE_SIZE) {
            recentIntervals.remove(0);
        }
        if (recentIntervals.size() == CONSISTENCY_SAMPLE_SIZE) {
            double stdDev = GrimMath.calculateSD(recentIntervals);
            if (stdDev < 5.0) {
                flag(V_CONSISTENCY.write(verbose()).f64(stdDev));
            }
        }
    }

    private void resetTracking() {
        tracking = false;
        confirmed = false;
        ticksSinceConfirmation = 0;
        lastPopTime = 0;
        totemPickupTimestamp = 0;
        wasCarryingTotem = false;
        recentIntervals.clear();
    }

    private OffhandAction getOffhandAction(WrapperPlayClientClickWindow click) {
        if (click.getWindowId() != 0) return null;

        if (click.getSlot() == Inventory.SLOT_OFFHAND) {
            ItemStack carried = click.getCarriedItemStack();
            if (carried == null || carried.isEmpty()) {
                carried = player.inventory.inventory.getCarried();
            }
            if (isDeathProtection(carried) || tracking) {
                return new OffhandAction(true);
            }
            return new OffhandAction(true);
        }

        Optional<Map<Integer, ItemStack>> slots = click.getSlots();
        if (slots.isPresent()) {
            ItemStack item = slots.get().get(Inventory.SLOT_OFFHAND);
            if (item != null) {
                return new OffhandAction(isDeathProtection(item) || tracking);
            }
        }

        Map<Integer, Optional<HashedStack>> hashedSlots = click.getHashedSlots();
        if (hashedSlots != null && hashedSlots.containsKey(Inventory.SLOT_OFFHAND)) {
            Optional<HashedStack> hashed = hashedSlots.get(Inventory.SLOT_OFFHAND);
            boolean isTotem = hashed != null && hashed.isPresent() && hashed.get().getItem() == ItemTypes.TOTEM_OF_UNDYING;
            return new OffhandAction(isTotem || tracking);
        }

        if (click.getWindowClickType() == WrapperPlayClientClickWindow.WindowClickType.SWAP && click.getButton() == 40) {
            int hoveredSlot = click.getSlot();
            if (hoveredSlot >= 0 && hoveredSlot < player.inventory.menu.getSlots().size()) {
                ItemStack hoveredItem = player.inventory.menu.getSlot(hoveredSlot).getItem();
                return new OffhandAction(isDeathProtection(hoveredItem) || tracking);
            }
            return new OffhandAction(true);
        }

        return null;
    }

    private static boolean isDeathProtection(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        return item.is(ItemTypes.TOTEM_OF_UNDYING)
                || item.getType() == ItemTypes.TOTEM_OF_UNDYING
                || item.getComponentOr(ComponentTypes.DEATH_PROTECTION, null) != null;
    }

    private record OffhandAction(boolean deathProtection) {}
}
