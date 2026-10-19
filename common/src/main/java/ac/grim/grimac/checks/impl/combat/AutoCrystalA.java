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

@CheckData(
        name = "AutoCrystalA",
        stableKey = "grim.combat.autocrystal.a",
        description = "Attacked an End Crystal before server spawn confirmation or predicted entity ID",
        experimental = true
)
public class AutoCrystalA extends Check implements PacketReceiveListener, PacketSendListener {

    private static final Verbose V_PREDICT = Verbose.of("type={str}, target={sint}, diff={sint}, trans={sint}/{sint}");

    private static final int NO_TRANSACTION = -1;
    private static final int NO_ENTITY_ID = -1;
    private static final int MAX_TRACKED_CRYSTALS = 64;
    private static final long RECENT_PLACEMENT_WINDOW_MS = 1500L;
    private static final double BUFFER_INCREMENT_UNSPAWNED = 1.5;
    private static final double BUFFER_INCREMENT_UNCONFIRMED = 1.0;
    private static final double BUFFER_VIOLATION_THRESHOLD = 1.0;
    private static final double BUFFER_DECAY = 0.1;

    private static final PacketTypeCommon[] RECEIVE_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.INTERACT_ENTITY
    };

    private static final PacketTypeCommon[] SEND_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.DESTROY_ENTITIES,
            PacketType.Play.Server.RESPAWN,
            PacketType.Play.Server.JOIN_GAME
    };

    private final Int2IntMap crystalSpawnTransactions = new Int2IntOpenHashMap();
    private final IntSet knownCrystalIds = new IntOpenHashSet();

    private int highestSpawnedCrystalId = NO_ENTITY_ID;
    private long lastCrystalPlaceTime;
    private double buffer;

    public AutoCrystalA(GrimPlayer player) {
        super(player);
        crystalSpawnTransactions.defaultReturnValue(NO_TRANSACTION);
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
    public PacketTypeCommon[] getHandledSendPacketTypes() {
        return SEND_PACKETS;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Server.SPAWN_ENTITY) {
            handleEntitySpawn(event);
            return;
        }

        if (packetType == PacketType.Play.Server.DESTROY_ENTITIES) {
            handleEntityDestroy(event);
            return;
        }

        if (packetType == PacketType.Play.Server.RESPAWN || packetType == PacketType.Play.Server.JOIN_GAME) {
            handleWorldReset();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            handleBlockPlacement(event);
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

    private void handleEntitySpawn(PacketSendEvent event) {
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
        if (spawn.getEntityType() != EntityTypes.END_CRYSTAL) {
            return;
        }

        final int entityId = spawn.getEntityId();
        if (knownCrystalIds.size() >= MAX_TRACKED_CRYSTALS) {
            knownCrystalIds.clear();
            crystalSpawnTransactions.clear();
        }

        knownCrystalIds.add(entityId);
        highestSpawnedCrystalId = Math.max(highestSpawnedCrystalId, entityId);

        event.getTasksAfterSend().add(() -> {
            player.sendTransaction();
            final int transaction = player.lastTransactionSent.get();
            crystalSpawnTransactions.put(entityId, transaction);
        });
    }

    private void handleEntityDestroy(PacketSendEvent event) {
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(event);
        for (int id : destroy.getEntityIds()) {
            crystalSpawnTransactions.remove(id);
            knownCrystalIds.remove(id);
        }
    }

    private void handleWorldReset() {
        crystalSpawnTransactions.clear();
        knownCrystalIds.clear();
        highestSpawnedCrystalId = NO_ENTITY_ID;
        lastCrystalPlaceTime = 0;
        buffer = 0.0;
    }

    private void handleBlockPlacement(PacketReceiveEvent event) {
        WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
        if (!isHoldingEndCrystal(place.getHand())) {
            return;
        }

        StateType clickedBlock = player.compensatedWorld.getBlock(place.getBlockPosition()).getType();
        if (isCrystalBaseBlock(clickedBlock)) {
            lastCrystalPlaceTime = System.currentTimeMillis();
        }
    }

    private boolean isHoldingEndCrystal(InteractionHand hand) {
        ItemStack held = hand == InteractionHand.OFF_HAND
                ? player.inventory.getOffHand()
                : player.inventory.getHeldItem();
        return held != null && held.getType() == ItemTypes.END_CRYSTAL;
    }

    private boolean isCrystalBaseBlock(StateType stateType) {
        return stateType == StateTypes.OBSIDIAN || stateType == StateTypes.BEDROCK;
    }

    private void handleAttack(PacketReceiveEvent event, int entityId) {
        final long now = System.currentTimeMillis();

        if (checkPredictedEntityId(event, entityId, now)) {
            crystalSpawnTransactions.remove(entityId);
            knownCrystalIds.remove(entityId);
            return;
        }

        if (checkUnconfirmedSpawn(event, entityId)) {
            crystalSpawnTransactions.remove(entityId);
            knownCrystalIds.remove(entityId);
            return;
        }

        if (isKnownCrystal(entityId)) {
            buffer = Math.max(0.0, buffer - BUFFER_DECAY);
            crystalSpawnTransactions.remove(entityId);
            knownCrystalIds.remove(entityId);
        }
    }

    private boolean checkPredictedEntityId(PacketReceiveEvent event, int entityId, long now) {
        final boolean recentlyPlaced = (now - lastCrystalPlaceTime) < RECENT_PLACEMENT_WINDOW_MS;
        if (!recentlyPlaced || highestSpawnedCrystalId == NO_ENTITY_ID || entityId <= highestSpawnedCrystalId) {
            return false;
        }

        buffer += BUFFER_INCREMENT_UNSPAWNED;
        if (buffer >= BUFFER_VIOLATION_THRESHOLD) {
            int idDiff = entityId - highestSpawnedCrystalId;
            flagAndCancel(event, V_PREDICT.write(verbose())
                    .str("id_predict_unspawned")
                    .sint(entityId)
                    .sint(idDiff)
                    .sint(0)
                    .sint(player.lastTransactionReceived.get()));
        }
        return true;
    }

    private boolean checkUnconfirmedSpawn(PacketReceiveEvent event, int entityId) {
        if (!isKnownCrystal(entityId)) {
            return false;
        }

        final int spawnTransaction = crystalSpawnTransactions.get(entityId);
        final int lastAcknowledgedTransaction = player.lastTransactionReceived.get();

        if (spawnTransaction == NO_TRANSACTION || lastAcknowledgedTransaction >= spawnTransaction) {
            return false;
        }

        final int transactionDiff = spawnTransaction - lastAcknowledgedTransaction;
        buffer += BUFFER_INCREMENT_UNCONFIRMED;

        if (buffer >= BUFFER_VIOLATION_THRESHOLD) {
            flagAndCancel(event, V_PREDICT.write(verbose())
                    .str("spawn_unconfirmed")
                    .sint(entityId)
                    .sint(transactionDiff)
                    .sint(spawnTransaction)
                    .sint(lastAcknowledgedTransaction));
        }
        return true;
    }

    private boolean isKnownCrystal(int entityId) {
        if (knownCrystalIds.contains(entityId)) {
            return true;
        }
        var compensated = player.compensatedEntities.getEntity(entityId);
        return compensated != null && compensated.getType() == EntityTypes.END_CRYSTAL;
    }

    private void flagAndCancel(PacketReceiveEvent event, Verbose.Writer verboseWriter) {
        if (flag(verboseWriter) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
