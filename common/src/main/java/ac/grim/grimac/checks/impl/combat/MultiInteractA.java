package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSpectateEntity;

import java.util.ArrayList;

@CheckData(name = "MultiInteractA", stableKey = "grim.multiinteract.multiple_targets", description = "Interacted with multiple entities in the same tick", experimental = true)
public class MultiInteractA extends Check implements PacketReceiveListener, PostPredictionListener {

    private static final Verbose V = Verbose.of("lastEntity={sint}, entity={sint}, lastSneaking={bool}, sneaking={bool}");

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.SPECTATE_ENTITY,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.CLIENT_TICK_END
    };

    private final ArrayList<FlagData> flags = new ArrayList<>();
    private int lastEntity;
    private boolean lastSneaking;
    private boolean hasInteracted;

    public MultiInteractA(final GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            int entity = packet.getEntityId();
            boolean sneaking = packet.isSneaking().orElse(false);
            onInteract(event, entity, sneaking);
        } else if (packetType == PacketType.Play.Client.ATTACK) {
            WrapperPlayClientAttack packet = new WrapperPlayClientAttack(event);
            onInteract(event, packet.getEntityId(), lastSneaking);
        } else if (packetType == PacketType.Play.Client.SPECTATE_ENTITY) {
            WrapperPlayClientSpectateEntity packet = new WrapperPlayClientSpectateEntity(event);
            onInteract(event, packet.getEntityId(), lastSneaking);
        }

        if (!player.cameraEntity.isSelf() || isTickPacket(packetType)) {
            hasInteracted = false;
        }
    }

    private void onInteract(PacketReceiveEvent event, int entity, boolean sneaking) {
        if (hasInteracted && (entity != lastEntity || sneaking != lastSneaking)) {
            if (!player.canSkipTicks()) {
                if (flag(V.write(verbose()).sint(lastEntity).sint(entity).bool(lastSneaking).bool(sneaking)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(lastEntity, entity, lastSneaking, sneaking));
            }
        }

        lastEntity = entity;
        lastSneaking = sneaking;
        hasInteracted = true;
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (flags.isEmpty()) return;

        if (player.canSkipTicks() && player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose()).sint(data.lastEntity()).sint(data.entity()).bool(data.lastSneaking()).bool(data.sneaking()));
            }
        }

        flags.clear();
    }

    private record FlagData(int lastEntity, int entity, boolean lastSneaking, boolean sneaking) {}
}
