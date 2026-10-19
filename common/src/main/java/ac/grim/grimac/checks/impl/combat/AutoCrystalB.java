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

@CheckData(
        name = "AutoCrystalB",
        stableKey = "grim.combat.autocrystal.b",
        description = "Automated crystal place-break timing consistency or artificial random jitter",
        experimental = true
)
public class AutoCrystalB extends Check implements PacketReceiveListener {

    private static final Verbose V_STATS = Verbose.of("pattern={str}, mean={f64:%.2f}ticks, stdDev={f64:%.2f}ticks, skew={f64:%.2f}, kurt={f64:%.2f}");

    private static final int WINDOW_SIZE = 16;
    private static final int MIN_SAMPLES_FOR_EVAL = 12;

    private static final int MAX_CRYSTAL_ATTACK_DELTA_TICKS = 20;

    private static final double LOW_VARIANCE_MAX_STDDEV = 0.5;
    private static final double LOW_VARIANCE_MAX_MEAN = 6.0;
    private static final double LOW_VARIANCE_BUFFER_INCREMENT = 2.0;

    private static final double UNIFORM_JITTER_MAX_STDDEV = 1.5;
    private static final double UNIFORM_JITTER_MAX_ABS_SKEW = 0.22;
    private static final double UNIFORM_JITTER_MAX_KURTOSIS = -0.85;
    private static final double UNIFORM_JITTER_BUFFER_INCREMENT = 1.8;

    private static final double INHUMAN_SPEED_MAX_MEAN = 1.25;
    private static final double INHUMAN_SPEED_MAX_STDDEV = 0.75;
    private static final double INHUMAN_SPEED_BUFFER_INCREMENT = 2.5;

    private static final double LEGIT_MIN_STDDEV = 0.44;
    private static final double LEGIT_MIN_SKEWNESS = 0.45;
    private static final double LEGIT_MIN_KURTOSIS = -0.5;
    private static final double LEGIT_BUFFER_DECAY = 0.35;

    private static final double BUFFER_VIOLATION_THRESHOLD = 5.0;
    private static final double BUFFER_MAX_CAP = 8.0;

    private static final PacketTypeCommon[] RECEIVE_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.CLIENT_TICK_END,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.INTERACT_ENTITY
    };

    private final double[] intervals = new double[WINDOW_SIZE];
    private int sampleIndex;
    private int sampleCount;
    private double buffer;

    private int tick;
    private int lastCrystalPlaceTick;
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

        if (isTickPacket(packetType)) {
            tick++;
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            handleBlockPlacement(event);
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

    private void handleBlockPlacement(PacketReceiveEvent event) {
        WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
        if (!isHoldingEndCrystal(place.getHand())) {
            return;
        }

        StateType clickedBlock = player.compensatedWorld.getBlock(place.getBlockPosition()).getType();
        if (isCrystalBaseBlock(clickedBlock)) {
            lastCrystalPlaceTick = tick;
            pendingCrystalHit = true;
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

    private void handleAttack(int entityId) {
        if (!pendingCrystalHit) {
            return;
        }

        final int delta = tick - lastCrystalPlaceTick;
        pendingCrystalHit = false;

        if (isConnectionUnstable()) {
            return;
        }

        if (isCrystalEntity(entityId) && isValidTimingSample(delta)) {
            addSample((double) delta);
            evaluateSamples();
        }
    }

    private boolean isConnectionUnstable() {
        return player.canSkipTicks() || !player.isTickingReliablyFor(3);
    }

    private boolean isCrystalEntity(int entityId) {
        var entity = player.compensatedEntities.getEntity(entityId);
        return entity != null && entity.getType() == EntityTypes.END_CRYSTAL;
    }

    private boolean isValidTimingSample(int delta) {
        return delta >= 0 && delta <= MAX_CRYSTAL_ATTACK_DELTA_TICKS;
    }

    private void addSample(double delta) {
        intervals[sampleIndex % WINDOW_SIZE] = delta;
        sampleIndex++;
        if (sampleCount < WINDOW_SIZE) {
            sampleCount++;
        }
    }

    private void evaluateSamples() {
        if (sampleCount < MIN_SAMPLES_FOR_EVAL) {
            return;
        }

        SampleStatistics stats = SampleStatistics.compute(intervals, sampleCount);

        if (isLowVariancePattern(stats)) {
            handleViolation("low_variance", stats, LOW_VARIANCE_BUFFER_INCREMENT);
            return;
        }

        if (isUniformJitterPattern(stats)) {
            handleViolation("uniform_jitter", stats, UNIFORM_JITTER_BUFFER_INCREMENT);
            return;
        }

        if (isInhumanSpeedPattern(stats)) {
            handleViolation("inhuman_speed", stats, INHUMAN_SPEED_BUFFER_INCREMENT);
            return;
        }

        if (isHumanVariancePattern(stats)) {
            buffer = Math.max(0.0, buffer - LEGIT_BUFFER_DECAY);
        }
    }

    private boolean isLowVariancePattern(SampleStatistics stats) {
        return stats.stdDev() < LOW_VARIANCE_MAX_STDDEV && stats.mean() <= LOW_VARIANCE_MAX_MEAN;
    }

    private boolean isUniformJitterPattern(SampleStatistics stats) {
        return stats.stdDev() < UNIFORM_JITTER_MAX_STDDEV
                && Math.abs(stats.skewness()) < UNIFORM_JITTER_MAX_ABS_SKEW
                && stats.excessKurtosis() < UNIFORM_JITTER_MAX_KURTOSIS;
    }

    private boolean isInhumanSpeedPattern(SampleStatistics stats) {
        return stats.mean() <= INHUMAN_SPEED_MAX_MEAN && stats.stdDev() < INHUMAN_SPEED_MAX_STDDEV;
    }

    private boolean isHumanVariancePattern(SampleStatistics stats) {
        return stats.stdDev() >= LEGIT_MIN_STDDEV
                && stats.skewness() > LEGIT_MIN_SKEWNESS
                && stats.excessKurtosis() > LEGIT_MIN_KURTOSIS;
    }

    private void handleViolation(String pattern, SampleStatistics stats, double bufferIncrement) {
        buffer += bufferIncrement;
        if (buffer >= BUFFER_VIOLATION_THRESHOLD) {
            flag(V_STATS.write(verbose())
                    .str(pattern)
                    .f64(stats.mean())
                    .f64(stats.stdDev())
                    .f64(stats.skewness())
                    .f64(stats.excessKurtosis()));
            buffer = Math.min(buffer, BUFFER_MAX_CAP);
        }
    }

    private record SampleStatistics(double mean, double stdDev, double skewness, double excessKurtosis) {

        public static SampleStatistics compute(double[] samples, int count) {
            double sum = 0.0;
            for (int i = 0; i < count; i++) {
                sum += samples[i];
            }
            final double mean = sum / count;

            double sumSqDiff = 0.0;
            for (int i = 0; i < count; i++) {
                double diff = samples[i] - mean;
                sumSqDiff += diff * diff;
            }
            final double variance = sumSqDiff / (count - 1);
            final double stdDev = Math.sqrt(variance);
            final double safeStdDev = Math.max(stdDev, 0.001);

            double m3 = 0.0;
            double m4 = 0.0;
            for (int i = 0; i < count; i++) {
                double norm = (samples[i] - mean) / safeStdDev;
                double normSq = norm * norm;
                m3 += normSq * norm;
                m4 += normSq * normSq;
            }

            final double skewness = m3 / count;
            final double excessKurtosis = (m4 / count) - 3.0;

            return new SampleStatistics(mean, stdDev, skewness, excessKurtosis);
        }
    }
}
