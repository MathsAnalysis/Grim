package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
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

@CheckData(name = "AutoCrystalB", stableKey = "grim.combat.autocrystal.b", description = "Automated crystal place-break timing consistency or artificial random jitter", experimental = true)
public class AutoCrystalB extends Check implements PacketReceiveListener {

    private static final Verbose V_STATS = Verbose.of("pattern={str}, mean={f64:%.2f}ms, stdDev={f64:%.2f}ms, skew={f64:%.2f}, kurt={f64:%.2f}");

    private static final int WINDOW_SIZE = 16;
    private static final int MIN_SAMPLES_FOR_EVAL = 12;

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] RECEIVE_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.INTERACT_ENTITY
    };

    private final double[] intervals = new double[WINDOW_SIZE];
    private int sampleIndex;
    private int sampleCount;
    private double buffer;

    private long lastCrystalPlaceTime;
    private boolean pendingCrystalHit;

    public AutoCrystalB(GrimPlayer player) {
        super(player);
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
                    pendingCrystalHit = true;
                }
            }
            return;
        }

        if (packetType == PacketType.Play.Client.ATTACK) {
            WrapperPlayClientAttack attack = new WrapperPlayClientAttack(event);
            handleAttack(attack.getEntityId());
            return;
        }

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                handleAttack(interact.getEntityId());
            }
        }
    }

    private void handleAttack(int entityId) {
        if (!pendingCrystalHit) return;

        final boolean isCrystal = player.compensatedEntities.getEntity(entityId) != null
                && player.compensatedEntities.getEntity(entityId).getType() == EntityTypes.END_CRYSTAL;

        final long now = System.currentTimeMillis();
        final long delta = now - lastCrystalPlaceTime;
        pendingCrystalHit = false;

        if (player.canSkipTicks() || !player.isTickingReliablyFor(3)) {
            return;
        }

        if (delta >= 0 && delta <= 1200L && (isCrystal || delta <= 400L)) {
            addSample((double) delta);
            evaluateSamples();
        }
    }

    private void addSample(double delta) {
        intervals[sampleIndex % WINDOW_SIZE] = delta;
        sampleIndex++;
        if (sampleCount < WINDOW_SIZE) {
            sampleCount++;
        }
    }

    private void evaluateSamples() {
        if (sampleCount < MIN_SAMPLES_FOR_EVAL) return;

        double sum = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            sum += intervals[i];
        }
        final double mean = sum / sampleCount;

        double sumSqDiff = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            double diff = intervals[i] - mean;
            sumSqDiff += diff * diff;
        }
        final double variance = sumSqDiff / (sampleCount - 1);
        final double stdDev = Math.sqrt(variance);

        final double safeStdDev = Math.max(stdDev, 0.001);

        double m3 = 0.0;
        double m4 = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            double norm = (intervals[i] - mean) / safeStdDev;
            double normSq = norm * norm;
            m3 += normSq * norm;
            m4 += normSq * normSq;
        }

        final double skewness = m3 / sampleCount;
        final double excessKurtosis = (m4 / sampleCount) - 3.0;

        if (stdDev < 7.5 && mean < 300.0) {
            buffer += 2.0;
            if (buffer >= 5.0) {
                flag(V_STATS.write(verbose())
                        .str("low_variance")
                        .f64(mean)
                        .f64(stdDev)
                        .f64(skewness)
                        .f64(excessKurtosis));
                buffer = Math.min(buffer, 8.0);
            }
            return;
        }

        if (stdDev < 35.0 && Math.abs(skewness) < 0.22 && excessKurtosis < -0.85) {
            buffer += 1.8;
            if (buffer >= 5.0) {
                flag(V_STATS.write(verbose())
                        .str("uniform_jitter")
                        .f64(mean)
                        .f64(stdDev)
                        .f64(skewness)
                        .f64(excessKurtosis));
                buffer = Math.min(buffer, 8.0);
            }
            return;
        }

        if (mean < 25.0 && stdDev < 15.0) {
            buffer += 2.5;
            if (buffer >= 5.0) {
                flag(V_STATS.write(verbose())
                        .str("inhuman_speed")
                        .f64(mean)
                        .f64(stdDev)
                        .f64(skewness)
                        .f64(excessKurtosis));
                buffer = Math.min(buffer, 8.0);
            }
            return;
        }

        if (stdDev >= 22.0 && skewness > 0.45 && excessKurtosis > -0.5) {
            buffer = Math.max(0.0, buffer - 0.35);
        }
    }
}
