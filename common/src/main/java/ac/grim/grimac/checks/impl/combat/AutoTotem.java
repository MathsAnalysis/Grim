package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.HashedStack;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeathCombatEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;

import java.util.Map;
import java.util.Optional;

@CheckData(name = "AutoTotem", stableKey = "grim.combat.autototem", description = "Refilled the offhand totem within a few ticks of it being consumed", experimental = true)
public class AutoTotem extends Check implements PacketReceiveListener, PacketSendListener {
    private static final Verbose V = Verbose.of("source={str}, ticks={uint}");

    private static final int TOTEM_STATUS = 35;
    private static final int FLAG_TICKS = 3;
    private static final int EXPIRE_TICKS = 40;
    private static final int RECENT_OFFHAND_TICKS = 10;

    private int tick;
    private int lastOffhandActionTick = -RECENT_OFFHAND_TICKS - 1;
    private boolean armed;
    private int ticksSincePop;

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

        if (event.getPacketType() == PacketType.Play.Server.RESPAWN || event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            armed = false;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.DEATH_COMBAT_EVENT && new WrapperPlayServerDeathCombatEvent(event).getPlayerId() == player.entityID) {
            armed = false;
            return;
        }

        if (event.getPacketType() != PacketType.Play.Server.ENTITY_STATUS) return;

        WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
        if (status.getEntityId() != player.entityID || status.getStatus() != TOTEM_STATUS) return;

        if (player.canSkipTicks() || !isOnlyOffhandTotemConsumed()) return;
        if (tick - lastOffhandActionTick <= RECENT_OFFHAND_TICKS) return;

        event.getTasksAfterSend().add(() -> {
            player.sendTransaction();
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), this::arm);
        });
    }

    private boolean isOnlyOffhandTotemConsumed() {
        ItemStack offHand = player.inventory.getOffHand();
        return isDeathProtection(offHand)
                && offHand.getAmount() == 1
                && !isDeathProtection(player.inventory.getHeldItem());
    }

    private void arm() {
        armed = true;
        ticksSincePop = 0;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;

        if (isTickPacket(event.getPacketType())) {
            tick++;
            if (armed && ++ticksSincePop > EXPIRE_TICKS) armed = false;
            return;
        }

        OffhandAction action = getOffhandAction(event);
        if (action == null) return;

        lastOffhandActionTick = tick;
        if (!armed || !action.deathProtection()) return;

        armed = false;
        if (ticksSincePop <= FLAG_TICKS) {
            String source = event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW ? "window" : "swap";
            flag(V.write(verbose()).str(source).uint(ticksSincePop));
        }
    }

    private OffhandAction getOffhandAction(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            if (click.getWindowId() != 0) return null;

            Optional<Map<Integer, ItemStack>> slots = click.getSlots();
            if (slots.isPresent()) {
                ItemStack item = slots.get().get(Inventory.SLOT_OFFHAND);
                return item == null ? null : new OffhandAction(isDeathProtection(item));
            }

            Map<Integer, Optional<HashedStack>> hashedSlots = click.getHashedSlots();
            if (hashedSlots == null || !hashedSlots.containsKey(Inventory.SLOT_OFFHAND)) return null;

            Optional<HashedStack> hashed = hashedSlots.get(Inventory.SLOT_OFFHAND);
            return new OffhandAction(hashed.isPresent() && hashed.get().getItem() == ItemTypes.TOTEM_OF_UNDYING);
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING
                && new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            return new OffhandAction(isDeathProtection(player.inventory.getHeldItem()));
        }

        return null;
    }

    private static boolean isDeathProtection(ItemStack item) {
        return item.is(ItemTypes.TOTEM_OF_UNDYING) || item.getComponentOr(ComponentTypes.DEATH_PROTECTION, null) != null;
    }

    private record OffhandAction(boolean deathProtection) {}
}
