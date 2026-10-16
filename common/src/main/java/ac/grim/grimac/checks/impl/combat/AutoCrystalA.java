package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

@CheckData(name = "AutoCrystalA", stableKey = "grim.combat.autocrystal.a", description = "Attacked an End Crystal before server spawn confirmation or predicted entity ID", experimental = true)
public class AutoCrystalA extends Check implements PacketReceiveListener, PacketSendListener {

    private static final Verbose V_PREDICT = Verbose.of("type={str}, target={sint}, diff={sint}, trans={sint}/{sint}");

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] RECEIVE_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.INTERACT_ENTITY
    };

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] SEND_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.DESTROY_ENTITIES,
            PacketType.Play.Server.RESPAWN,
            PacketType.Play.Server.JOIN_GAME
    };

    private final Int2IntMap crystalSpawnTransactions = new Int2IntOpenHashMap();
    private final IntSet knownCrystalIds = new IntOpenHashSet();
    private int highestSpawnedCrystalId = -1;

    private long lastCrystalPlaceTime;
    private double buffer;

    public AutoCrystalA(GrimPlayer player) {
        super(player);
        crystalSpawnTransactions.defaultReturnValue(-1);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return RECEIVE_PACKETS;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            if (spawn.getEntityType() == EntityTypes.END_CRYSTAL) {
                final int entityId = spawn.getEntityId();
                knownCrystalIds.add(entityId);
                highestSpawnedCrystalId = Math.max(highestSpawnedCrystalId, entityId);

                event.getTasksAfterSend().add(() -> {
                    player.sendTransaction();
                    final int transaction = player.lastTransactionSent.get();
                    crystalSpawnTransactions.put(entityId, transaction);
                });
            }
            return;
        }

        if (packetType == PacketType.Play.Server.DESTROY_ENTITIES) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(event);
            for (int id : destroy.getEntityIds()) {
                crystalSpawnTransactions.remove(id);
                knownCrystalIds.remove(id);
            }
            return;
        }

        if (packetType == PacketType.Play.Server.RESPAWN || packetType == PacketType.Play.Server.JOIN_GAME) {
            crystalSpawnTransactions.clear();
            knownCrystalIds.clear();
            highestSpawnedCrystalId = -1;
            lastCrystalPlaceTime = 0;
            buffer = 0.0;
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
            ItemStack held = place.getHand() == InteractionHand.OFF_HAND ? player.inventory.getOffHand() : player.inventory.getHeldItem();
            if (held.getType() == ItemTypes.END_CRYSTAL) {
                StateType clicked = player.compensatedWorld.getBlock(place.getBlockPosition()).getType();
                if (clicked == StateTypes.OBSIDIAN || clicked == StateTypes.BEDROCK) {
                    lastCrystalPlaceTime = System.currentTimeMillis();
                }
            }
            return;
        }

        if (packetType == PacketType.Play.Client.ATTACK) {
            WrapperPlayClientAttack attack = new WrapperPlayClientAttack(event);
            handleAttack(event, attack.getEntityId());
            return;
        }

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                handleAttack(event, interact.getEntityId());
            }
        }
    }

    private void handleAttack(PacketReceiveEvent event, int entityId) {
        final boolean isKnownCrystal = knownCrystalIds.contains(entityId)
                || (player.compensatedEntities.getEntity(entityId) != null
                && player.compensatedEntities.getEntity(entityId).getType() == EntityTypes.END_CRYSTAL);

        final long now = System.currentTimeMillis();
        final boolean recentlyPlaced = (now - lastCrystalPlaceTime) < 1500L;

        if (recentlyPlaced && highestSpawnedCrystalId != -1 && entityId > highestSpawnedCrystalId) {
            buffer += 1.5;
            if (buffer >= 1.5) {
                if (flag(V_PREDICT.write(verbose())
                        .str("id_predict_unspawned")
                        .sint(entityId)
                        .sint(entityId - highestSpawnedCrystalId)
                        .sint(0)
                        .sint(player.lastTransactionReceived.get()))
                        && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
            return;
        }

        if (isKnownCrystal) {
            final int spawnTrans = crystalSpawnTransactions.get(entityId);
            final int lastAck = player.lastTransactionReceived.get();

            if (spawnTrans != -1 && lastAck < spawnTrans) {
                final int transDiff = spawnTrans - lastAck;
                buffer += 1.0;
                if (buffer >= 1.0) {
                    if (flag(V_PREDICT.write(verbose())
                            .str("spawn_unconfirmed")
                            .sint(entityId)
                            .sint(transDiff)
                            .sint(spawnTrans)
                            .sint(lastAck))
                            && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }
                return;
            }

            buffer = Math.max(0.0, buffer - 0.1);
        }
    }
}
