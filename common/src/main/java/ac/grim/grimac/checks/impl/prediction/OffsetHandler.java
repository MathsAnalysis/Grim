package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

@CheckData(
        name = "Simulation",
        stableKey = "grim.prediction.simulation",
        description = "Moved differently than predicted movement simulation",
        decay = 0.02
)
public class OffsetHandler extends Check implements PostPredictionListener {

    private static final Verbose V = Verbose.of("{offset}");
    private static final CompletePredictionEvent.Channel COMPLETE_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class);

    private static final AtomicInteger FLAG_COUNTER = new AtomicInteger(0);

    private static final double MIN_ADVANTAGE_EPSILON = 1.0E-5;
    private static final double MAX_LENIENCE_CAP = 1.0;

    private double setbackDecayMultiplier;
    private double threshold;
    private double immediateSetbackThreshold;
    private double maxAdvantage;
    private double maxCeiling;
    private double setbackViolationThreshold;

    private double advantageGained = 0.0;

    public OffsetHandler(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            return;
        }

        final double offset = predictionComplete.getOffset();

        if (Double.isNaN(offset) || Double.isInfinite(offset) || offset <= 0.0) {
            decayAdvantage();
            return;
        }

        if (COMPLETE_CHANNEL.fire(player, this, offset)) {
            return;
        }

        if (player.getSetbackTeleportUtil().hasPendingSetback()) {
            removeOffsetLenience();
            return;
        }

        if (offset >= threshold || offset >= immediateSetbackThreshold) {
            handleViolation(predictionComplete, offset);
        } else {
            decayAdvantage();
            removeOffsetLenience();
        }
    }

    private void handleViolation(final PredictionComplete predictionComplete, final double offset) {
        advantageGained = Math.min(advantageGained + offset, maxCeiling);

        giveOffsetLenienceNextTick(offset);

        final int flagId = (FLAG_COUNTER.getAndIncrement() & 0xFF) + 1;

        final boolean flagged = flag(
                V.write(verbose()).f64(offset),
                () -> humanFormattedOffset(offset) + " /gl " + flagId
        );

        if (flagged) {
            predictionComplete.setIdentifier(flagId);

            final boolean shouldSetback = (advantageGained >= maxAdvantage || offset >= immediateSetbackThreshold)
                    && !isNoSetbackPermission()
                    && violations >= setbackViolationThreshold;

            if (shouldSetback) {
                advantageGained = 0.0;
                removeOffsetLenience();
                player.getSetbackTeleportUtil().executeViolationSetback();
            }
        }
    }

    private void decayAdvantage() {
        advantageGained *= setbackDecayMultiplier;
        if (advantageGained < MIN_ADVANTAGE_EPSILON) {
            advantageGained = 0.0;
        }
    }

    private void giveOffsetLenienceNextTick(double offset) {
        double minimizedOffset = Math.min(offset, MAX_LENIENCE_CAP);

        player.uncertaintyHandler.lastHorizontalOffset = minimizedOffset;
        player.uncertaintyHandler.lastVerticalOffset = minimizedOffset;
    }

    private void removeOffsetLenience() {
        player.uncertaintyHandler.lastHorizontalOffset = 0.0;
        player.uncertaintyHandler.lastVerticalOffset = 0.0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        setbackDecayMultiplier = config.getDoubleElse("Simulation.setback-decay-multiplier", 0.999);
        threshold = config.getDoubleElse("Simulation.threshold", 0.001);
        immediateSetbackThreshold = config.getDoubleElse("Simulation.immediate-setback-threshold", 0.1);
        maxAdvantage = config.getDoubleElse("Simulation.max-advantage", 1.0);
        maxCeiling = config.getDoubleElse("Simulation.max-ceiling", 4.0);
        setbackViolationThreshold = config.getDoubleElse("Simulation.setback-violation-threshold", 1.0);

        if (maxAdvantage == -1.0) maxAdvantage = Double.MAX_VALUE;
        if (immediateSetbackThreshold == -1.0) immediateSetbackThreshold = Double.MAX_VALUE;

        advantageGained = 0.0;
    }

    public boolean doesOffsetFlag(double offset) {
        return offset >= threshold;
    }

    public static String humanFormattedOffset(double offset) {
        if (offset < 0.001) {
            String str = String.format(java.util.Locale.ROOT, "%.4E", offset);
            return str.replace("E-0", "E-");
        }
        return String.format(java.util.Locale.ROOT, "%.6f", offset);
    }
}

