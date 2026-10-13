package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.EntityVisibility;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;

public class PacketEntityHider extends PacketListenerAbstract {

    public PacketEntityHider() {
        super(PacketListenerPriority.HIGHEST);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;

        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player != null) player.entityVisibility.tick();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!GrimAPI.INSTANCE.getConfigManager().isExperimentalEntityHiding()) return;

        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) return;
        EntityVisibility visibility = player.entityVisibility;

        if (event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer spawn = new WrapperPlayServerSpawnPlayer(event);
            if (visibility.onPlayerSpawn(spawn.getEntityId(), spawn.getUUID(), true, spawn.getEntityMetadata())) {
                event.setCancelled(true);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            if (spawn.getEntityType() == EntityTypes.PLAYER && spawn.getUUID().isPresent()
                    && visibility.onPlayerSpawn(spawn.getEntityId(), spawn.getUUID().get(), false, null)) {
                event.setCancelled(true);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            for (int entityId : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
                visibility.onDestroy(entityId);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            if (visibility.isHidden(new WrapperPlayServerEntityRelativeMove(event).getEntityId())) event.setCancelled(true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            if (visibility.isHidden(new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId())) event.setCancelled(true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_ROTATION) {
            if (visibility.isHidden(new WrapperPlayServerEntityRotation(event).getEntityId())) event.setCancelled(true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_TELEPORT) {
            if (visibility.isHidden(new WrapperPlayServerEntityTeleport(event).getEntityId())) event.setCancelled(true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            if (visibility.isHidden(new WrapperPlayServerEntityPositionSync(event).getId())) event.setCancelled(true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(event);
            if (visibility.onMetadata(metadata.getEntityId(), metadata.getEntityMetadata())) event.setCancelled(true);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            WrapperPlayServerEntityEquipment equipment = new WrapperPlayServerEntityEquipment(event);
            if (visibility.onEquipment(equipment.getEntityId(), equipment.getEquipment())) event.setCancelled(true);
        }
    }
}
