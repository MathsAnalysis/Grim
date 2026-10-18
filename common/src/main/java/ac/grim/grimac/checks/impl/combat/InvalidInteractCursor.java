package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

@CheckData(name = "InvalidInteractCursor", stableKey = "grim.badpackets.invalid_interact_vector", description = "Sent an entity interaction vector outside the target player's hitbox")
public class InvalidInteractCursor extends Check implements PacketReceiveListener {

    private static final Verbose V = Verbose.of("{f64:%.5f}/{f64:%.5f}/{f64:%.5f}");

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.INTERACT_ENTITY
    };

    private final double maxHorizontalDisplacement;
    private final double minVerticalDisplacement;
    private final double maxVerticalDisplacement;

    public InvalidInteractCursor(final GrimPlayer player) {
        super(player);
        double expansion = player.getClientVersion().isOlderThan(ClientVersion.V_1_9) ? 0.1f : 0;
        maxHorizontalDisplacement = 0.3001 + expansion;
        minVerticalDisplacement = -0.0001 - expansion;
        maxVerticalDisplacement = 1.8001 + expansion;
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            final WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) return;
            Vector3d targetVector = wrapper.getLocation();
            if (targetVector == null) return;

            if (!Double.isFinite(targetVector.x) || !Double.isFinite(targetVector.y) || !Double.isFinite(targetVector.z)) {
                flag(V.write(verbose()).f64(targetVector.x).f64(targetVector.y).f64(targetVector.z));
                return;
            }

            final PacketEntity packetEntity = player.compensatedEntities.getEntity(wrapper.getEntityId());
            if (packetEntity == null) {
                return;
            }

            if (!EntityTypes.PLAYER.equals(packetEntity.getType())) {
                return;
            }

            final float scale = (float) packetEntity.getAttributeValue(Attributes.SCALE);
            if (targetVector.y > (minVerticalDisplacement * scale) && targetVector.y < (maxVerticalDisplacement * scale)
                    && Math.abs(targetVector.x) < (maxHorizontalDisplacement * scale)
                    && Math.abs(targetVector.z) < (maxHorizontalDisplacement * scale)) {
                return;
            }

            flag(V.write(verbose()).f64(targetVector.x).f64(targetVector.y).f64(targetVector.z));
        }
    }
}
