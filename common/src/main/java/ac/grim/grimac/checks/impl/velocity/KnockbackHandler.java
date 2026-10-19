package ac.grim.grimac.checks.impl.velocity;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Deque;
import java.util.LinkedList;

@CheckData(name = "AntiKB", stableKey = "grim.velocity.anti_knockback", alternativeName = "AntiKnockback", configName = "Knockback", description = "Did not take the expected entity knockback", setback = 10, decay = 0.025)
public class KnockbackHandler extends Check implements PacketSendListener, PostPredictionListener {
    private static final Verbose V = Verbose.of("[ignored knockback|o: {offset}]");

    private final Deque<VelocityData> firstBreadMap = new LinkedList<>();
    private final Deque<VelocityData> lastKnockbackKnownTaken = new LinkedList<>();
    private VelocityData firstBreadOnlyKnockback = null;
    @Getter
    private boolean knockbackPointThree = false;

    private double offsetToFlag;
    private double maxAdv, immediate, ceiling, multiplier;
    private double threshold;

    public KnockbackHandler(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity velocity = new WrapperPlayServerEntityVelocity(event);
            int entityId = velocity.getEntityId();

            if (player.compensatedEntities.serverPlayerVehicle != null && entityId != player.compensatedEntities.serverPlayerVehicle) {
                return;
            }
            if (player.compensatedEntities.serverPlayerVehicle == null && entityId != player.entityID) {
                return;
            }

            Vector3d playerVelocity = velocity.getVelocity();

            if (Math.abs(playerVelocity.getX()) < 1e-4 && Math.abs(playerVelocity.getZ()) < 1e-4 && playerVelocity.getY() <= 0) {
                return;
            }

            if (playerVelocity.getY() == -0.04) {
                velocity.setVelocity(playerVelocity.add(new Vector3d(0, 1 / 8000D, 0)));
                playerVelocity = velocity.getVelocity();
                event.markForReEncode(true);
            }

            playerVelocity = VectorPrecisionConverter.convert(player.getClientVersion(), playerVelocity);

            player.sendTransaction();
            addPlayerKnockback(entityId, player.lastTransactionSent.get(), new Vector3dm(playerVelocity.getX(), playerVelocity.getY(), playerVelocity.getZ()));
            event.getTasksAfterSend().add(player::sendTransaction);
        }
    }

    @NotNull
    public Pair<VelocityData, Vector3dm> getFutureKnockback() {
        if (!firstBreadMap.isEmpty()) {
            VelocityData data = firstBreadMap.peek();
            return new Pair<>(data, data != null ? data.vector : null);
        }

        if (!lastKnockbackKnownTaken.isEmpty()) {
            VelocityData data = lastKnockbackKnownTaken.peek();
            return new Pair<>(data, data != null ? data.vector : null);
        }

        if (player.firstBreadKB != null && player.likelyKB == null) {
            VelocityData data = player.firstBreadKB;
            return new Pair<>(data, data.vector.clone());
        } else if (player.likelyKB != null) {
            VelocityData data = player.likelyKB;
            return new Pair<>(data, data.vector.clone());
        }
        return new Pair<>(null, null);
    }

    private void addPlayerKnockback(int entityID, int breadOne, @NotNull Vector3dm knockback) {
        firstBreadMap.add(new VelocityData(entityID, breadOne, player.getSetbackTeleportUtil().isSendingSetback, knockback));
    }

    public VelocityData calculateRequiredKB(int entityID, int transaction, boolean isJustTesting) {
        if (firstBreadMap.isEmpty() && lastKnockbackKnownTaken.isEmpty()) {
            return null;
        }

        tickKnockback(transaction);

        VelocityData returnLastKB = null;
        for (VelocityData data : lastKnockbackKnownTaken) {
            if (data.entityID == entityID)
                returnLastKB = data;
        }

        if (!isJustTesting) {
            lastKnockbackKnownTaken.clear();
        }
        return returnLastKB;
    }

    private void tickKnockback(int transactionID) {
        firstBreadOnlyKnockback = null;
        if (firstBreadMap.isEmpty()) return;
        VelocityData data = firstBreadMap.peek();
        while (data != null) {
            if (data.transaction == transactionID) {
                firstBreadOnlyKnockback = new VelocityData(data.entityID, data.transaction, data.isSetback, data.vector);
                firstBreadOnlyKnockback.offset = data.offset;
                break;
            } else if (data.transaction < transactionID) {
                VelocityData velocityData = new VelocityData(data.entityID, data.transaction, data.isSetback, data.vector);
                velocityData.offset = data.offset;

                lastKnockbackKnownTaken.add(velocityData);
                firstBreadOnlyKnockback = null;
                firstBreadMap.poll();
                data = firstBreadMap.peek();
            } else {
                break;
            }
        }
    }

    public void forceExempt() {
        if (player.firstBreadKB != null) {
            player.firstBreadKB.offset = 0;
        }

        if (player.likelyKB != null) {
            player.likelyKB.offset = 0;
        }
    }

    public void onTeleport(int teleportTransaction, boolean isRelativeVelocity) {
        forceExempt();
        if (!isRelativeVelocity) {
            firstBreadMap.removeIf(data -> data.transaction < teleportTransaction);
            lastKnockbackKnownTaken.removeIf(data -> data.transaction < teleportTransaction);
            if (firstBreadOnlyKnockback != null && firstBreadOnlyKnockback.transaction < teleportTransaction) {
                firstBreadOnlyKnockback = null;
            }
        }
        for (VelocityData data : firstBreadMap) {
            if (data.transaction == teleportTransaction) {
                data.offset = 0;
            }
        }
        for (VelocityData data : lastKnockbackKnownTaken) {
            if (data.transaction == teleportTransaction) {
                data.offset = 0;
            }
        }
        if (firstBreadOnlyKnockback != null && firstBreadOnlyKnockback.transaction == teleportTransaction) {
            firstBreadOnlyKnockback.offset = 0;
        }
    }

    public void setPointThree(boolean isPointThree) {
        knockbackPointThree = knockbackPointThree || isPointThree;
    }

    public void handlePredictionAnalysis(double offset) {
        if (player.firstBreadKB != null) {
            player.firstBreadKB.offset = Math.min(player.firstBreadKB.offset, offset);
        }

        if (player.likelyKB != null) {
            player.likelyKB.offset = Math.min(player.likelyKB.offset, offset);
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        double offset = predictionComplete.getOffset();
        if (!predictionComplete.isChecked() || predictionComplete.getData().isTeleport()) {
            forceExempt();
            return;
        }

        if (player.isClimbing || Collisions.onClimbable(player, player.x, player.y, player.z)) {
            forceExempt();
            return;
        }

        boolean wasZero = knockbackPointThree;
        knockbackPointThree = false;

        if (player.likelyKB == null && player.firstBreadKB == null) {
            return;
        }

        if (player.predictedVelocity.isFirstBreadKb()) {
            firstBreadOnlyKnockback = null;
            firstBreadMap.poll();
        }

        if (wasZero || player.predictedVelocity.isKnockback()) {
            if (player.firstBreadKB != null) {
                player.firstBreadKB.offset = Math.min(player.firstBreadKB.offset, offset);
            }

            if (player.likelyKB != null) {
                player.likelyKB.offset = Math.min(player.likelyKB.offset, offset);
            }
        }

        if (player.likelyKB != null) {
            if (player.likelyKB.offset > offsetToFlag) {
                threshold = Math.min(threshold + player.likelyKB.offset, ceiling);
                if (player.likelyKB.isSetback) {
                    if (!isNoSetbackPermission()) {
                        player.getSetbackTeleportUtil().executeViolationSetback();
                    }
                } else {
                    boolean ignored = player.likelyKB.offset == Integer.MAX_VALUE;
                    if (flag(V.write(verbose()).bool(ignored).f64(player.likelyKB.offset))) {
                        if (player.likelyKB.offset >= immediate || threshold >= maxAdv) {
                            setbackIfAboveSetbackVL();
                        }
                    } else {
                        reward();
                    }
                }
            } else if (threshold > 0.05) {
                threshold *= multiplier;
            }
        }
    }

    public boolean shouldIgnoreForPrediction(VectorData data) {
        if (data.isKnockback() && data.isFirstBreadKb()) {
            return player.firstBreadKB.offset > offsetToFlag;
        }
        return false;
    }

    public boolean wouldFlag() {
        return (player.likelyKB != null && player.likelyKB.offset > offsetToFlag) || (player.firstBreadKB != null && player.firstBreadKB.offset > offsetToFlag);
    }

    public VelocityData calculateFirstBreadKnockback(int entityID, int transaction) {
        if (firstBreadMap.isEmpty() && lastKnockbackKnownTaken.isEmpty() && firstBreadOnlyKnockback == null) {
            return null;
        }
        tickKnockback(transaction);
        if (firstBreadOnlyKnockback != null && firstBreadOnlyKnockback.entityID == entityID)
            return firstBreadOnlyKnockback;
        return null;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        offsetToFlag = config.getDoubleElse("Knockback.threshold", 0.001);
        maxAdv = config.getDoubleElse("Knockback.max-advantage", 1);
        immediate = config.getDoubleElse("Knockback.immediate-setback-threshold", 0.1);
        multiplier = config.getDoubleElse("Knockback.setback-decay-multiplier", 0.999);
        ceiling = config.getDoubleElse("Knockback.max-ceiling", 4);
        if (maxAdv < 0) maxAdv = Double.MAX_VALUE;
        if (immediate < 0) immediate = Double.MAX_VALUE;
    }
}
