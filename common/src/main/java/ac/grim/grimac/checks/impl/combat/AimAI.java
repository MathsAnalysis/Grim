package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.aim.processor.AimProcessor;
import ac.grim.grimac.checks.impl.combat.ai.AimAIModel;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

import java.util.ArrayDeque;
import java.util.Deque;

@CheckData(name = "AimAI", alternativeName = "AimML", stableKey = "grim.combat.aimai", description = "AI neural network scoring combat aim and rotation patterns", experimental = false)
public class AimAI extends Check implements RotationListener, PacketReceiveListener {

    private static final Verbose V_AI = Verbose.of("score={f64:%.2f}, gcd={f64:%.2f}, snap={f64:%.2f}, lock={f64:%.2f}, robot={f64:%.2f}");

    private static final int WINDOW_SIZE = 30;
    private static final int MIN_SAMPLES = 20;

    public record AIFeatures(
            double score,
            double gcdAnomaly,
            double attackSnap,
            double snapBack,
            double centerLock,
            double roboticSmoothness,
            double linearTracking,
            double switchSnap,
            double pitchFreeze,
            int sampleCount
    ) {}

    private record Sample(
            float deltaYaw,
            float deltaPitch,
            double angleToTarget,
            double gcdError,
            boolean isSignFlip,
            double jerk,
            boolean attacked,
            int targetEntityId
    ) {}

    private final Deque<Sample> samples = new ArrayDeque<>(WINDOW_SIZE);

    private long lastAttackTime;
    private int lastTargetEntityId = -1;
    private boolean attackedThisTick;

    private float prevDeltaYaw;
    private float prevDeltaPitch;
    private double prevJerk;

    private double scoreBuffer;
    private volatile double latestScore = 0.0;
    private volatile AIFeatures latestFeatures = new AIFeatures(0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public AimAI(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim || player.gamemode == GameMode.SPECTATOR) return;

        final PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.ATTACK) {
            handleAttack(new WrapperPlayClientAttack(event).getEntityId());
        } else if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                handleAttack(interact.getEntityId());
            }
        }
    }

    private void handleAttack(int entityId) {
        lastAttackTime = System.currentTimeMillis();
        lastTargetEntityId = entityId;
        attackedThisTick = true;
    }

    @Override
    public void process(RotationUpdate update) {
        if (player.disableGrim || player.gamemode == GameMode.SPECTATOR) return;

        final long now = System.currentTimeMillis();
        final boolean inCombat = (now - lastAttackTime) <= 1200L;

        if (!inCombat) {
            attackedThisTick = false;
            return;
        }

        float deltaYaw = update.deltaYawABS();
        float deltaPitch = update.deltaPitchABS();

        if (deltaYaw < 0.05f && deltaPitch < 0.05f) {
            attackedThisTick = false;
            return;
        }

        boolean isSignFlip = (update.deltaYaw() * prevDeltaYaw < 0) || (update.deltaPitch() * prevDeltaPitch < 0);
        double currentAccel = Math.abs(deltaYaw - prevDeltaYaw);
        double jerk = Math.abs(currentAccel - prevJerk);

        double gcdError = calculateGcdError(deltaYaw, deltaPitch);
        double angleToTarget = calculateAngleToTarget();

        samples.addLast(new Sample(deltaYaw, deltaPitch, angleToTarget, gcdError, isSignFlip, jerk, attackedThisTick, lastTargetEntityId));
        if (samples.size() > WINDOW_SIZE) {
            samples.removeFirst();
        }

        prevDeltaYaw = update.deltaYaw();
        prevDeltaPitch = update.deltaPitch();
        prevJerk = currentAccel;
        attackedThisTick = false;

        if (samples.size() >= MIN_SAMPLES) {
            evaluateModel();
        }
    }

    private double calculateGcdError(float deltaYaw, float deltaPitch) {
        AimProcessor aimProcessor = player.checkManager.get(AimProcessor.class);
        if (aimProcessor == null) return -1.0;

        double divisorYaw = aimProcessor.divisorYaw;
        if (divisorYaw > 0.001 && deltaYaw > 0.15f) {
            double remainder = deltaYaw % divisorYaw;
            return Math.min(remainder, divisorYaw - remainder) / divisorYaw;
        }
        return -1.0;
    }

    private double calculateAngleToTarget() {
        if (lastTargetEntityId == -1) return 10.0;

        PacketEntity target = player.compensatedEntities.entityMap.get(lastTargetEntityId);
        if (target == null || target.isDead) return 10.0;

        SimpleCollisionBox box = target.getPossibleCollisionBoxes();
        if (box == null) return 10.0;

        double centerX = (box.minX + box.maxX) / 2.0;
        double centerY = (box.minY + box.maxY) / 2.0;
        double centerZ = (box.minZ + box.maxZ) / 2.0;

        double dx = centerX - player.x;
        double dy = centerY - (player.y + player.getEyeHeight());
        double dz = centerZ - player.z;

        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.1) return 0.0;

        double dirX = dx / length;
        double dirY = dy / length;
        double dirZ = dz / length;

        double yawRad = Math.toRadians(player.yaw);
        double pitchRad = Math.toRadians(player.pitch);
        double lookX = -Math.cos(pitchRad) * Math.sin(yawRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(pitchRad) * Math.cos(yawRad);

        double dot = Math.max(-1.0, Math.min(1.0, lookX * dirX + lookY * dirY + lookZ * dirZ));
        return Math.toDegrees(Math.acos(dot));
    }

    private void evaluateModel() {
        int n = samples.size();
        if (n < MIN_SAMPLES) return;

        Sample[] sampleArr = samples.toArray(new Sample[0]);

        // 1. GCD Anomaly: proportion of non-trivial rotations that violate sensitivity divisor
        double gcdAnomaly = 0.0;
        int gcdEvaluated = 0;
        int gcdViolations = 0;
        for (Sample s : sampleArr) {
            if (s.deltaYaw() > 0.15f && s.gcdError() >= 0.0) {
                gcdEvaluated++;
                if (s.gcdError() > 0.18) {
                    gcdViolations++;
                }
            }
        }
        if (gcdEvaluated >= 6) {
            gcdAnomaly = Math.min(1.0, (double) gcdViolations / gcdEvaluated);
        }

        // 2. Attack Snap: crosshair jumps abruptly on attack tick compared to prior ticks
        double attackSnap = 0.0;
        int snapCount = 0;
        Sample prevS = null;
        for (Sample s : sampleArr) {
            if (s.attacked() && prevS != null) {
                if (s.deltaYaw() > 12.0f && prevS.deltaYaw() < 4.0f) {
                    snapCount++;
                }
            }
            prevS = s;
        }
        if (snapCount > 0) {
            attackSnap = Math.min(1.0, snapCount * 0.45);
        }

        // 3. Snap-Back / Silent Aim: crosshair immediately reverses/snaps back within 1 tick after attack
        double snapBack = 0.0;
        for (int i = 1; i < sampleArr.length - 1; i++) {
            Sample curr = sampleArr[i];
            Sample next = sampleArr[i + 1];
            if (curr.attacked() && curr.deltaYaw() > 10.0f) {
                if (next.isSignFlip() && next.deltaYaw() > 8.0f) {
                    snapBack = Math.min(1.0, snapBack + 0.60);
                }
            }
        }

        // 4. Center-Lock Invariance: variance of angleToTarget when tracking moving target
        double sumAngle = 0;
        for (Sample s : sampleArr) sumAngle += s.angleToTarget();
        double meanAngle = sumAngle / n;
        double varAngle = 0;
        for (Sample s : sampleArr) {
            double da = s.angleToTarget() - meanAngle;
            varAngle += da * da;
        }
        varAngle /= n;
        double stdAngle = Math.sqrt(varAngle);

        // Legit crosshair wanders across the hitbox (stdAngle > 0.35 deg).
        // Cheats locked to 3D center coordinate have stdAngle < 0.07 deg and meanAngle < 0.70 deg.
        double centerLock = 0.0;
        if (meanAngle < 0.70 && stdAngle < 0.07) {
            centerLock = Math.min(1.0, (0.07 - stdAngle) / 0.07);
        }

        // 5. Robotic Constant Velocity / Low Jerk Variance
        double sumYaw = 0;
        double sumJerk = 0;
        for (Sample s : sampleArr) {
            sumYaw += s.deltaYaw();
            sumJerk += s.jerk();
        }
        double meanYaw = sumYaw / n;
        double meanJerk = sumJerk / n;
        double varJerk = 0;
        for (Sample s : sampleArr) {
            double dj = s.jerk() - meanJerk;
            varJerk += dj * dj;
        }
        varJerk /= n;

        double roboticSmoothness = 0.0;
        if (meanYaw > 1.8 && varJerk < 0.003) {
            roboticSmoothness = Math.min(1.0, (0.003 - varJerk) / 0.003);
        }

        // 6. Synthetic Linear Path
        double sumPitch = 0;
        for (Sample s : sampleArr) sumPitch += s.deltaPitch();
        double meanPitch = sumPitch / n;
        double varYaw = 0, varPitch = 0, covYawPitch = 0;
        for (Sample s : sampleArr) {
            double dy = s.deltaYaw() - meanYaw;
            double dp = s.deltaPitch() - meanPitch;
            varYaw += dy * dy;
            varPitch += dp * dp;
            covYawPitch += dy * dp;
        }
        varYaw /= n;
        varPitch /= n;
        double stdYaw = Math.sqrt(varYaw);
        double stdPitch = Math.sqrt(varPitch);
        double linearity = (stdYaw > 0.001 && stdPitch > 0.001)
                ? Math.abs(covYawPitch / (n * stdYaw * stdPitch))
                : 0.0;

        double linearTracking = 0.0;
        if (linearity > 0.985 && (sumYaw + sumPitch) > 25.0) {
            linearTracking = 0.85;
        }

        // 7. Target Switch Snap: 1-tick switch between two different entities with >35 deg snap and attack
        double switchSnap = 0.0;
        for (int i = 0; i < sampleArr.length - 1; i++) {
            Sample s1 = sampleArr[i];
            Sample s2 = sampleArr[i + 1];
            if (s1.targetEntityId() != -1 && s2.targetEntityId() != -1
                    && s1.targetEntityId() != s2.targetEntityId()
                    && s2.deltaYaw() > 35.0f && s2.attacked()) {
                switchSnap = Math.min(1.0, switchSnap + 0.85);
            }
        }

        // 8. Pitch Freeze: large yaw movement (>8.0 deg/tick) while pitch delta is 0.00000
        double pitchFreeze = 0.0;
        if (meanYaw > 8.0) {
            double maxPitch = 0.0;
            for (Sample s : sampleArr) {
                if (s.deltaPitch() > maxPitch) maxPitch = s.deltaPitch();
            }
            if (maxPitch < 0.0001) {
                pitchFreeze = 0.90;
            }
        }

        double[] x = new double[AimAIModel.INPUT_SIZE];
        x[0] = gcdAnomaly;
        x[1] = attackSnap;
        x[2] = snapBack;
        x[3] = centerLock;
        x[4] = roboticSmoothness;
        x[5] = linearTracking;
        x[6] = switchSnap;
        x[7] = pitchFreeze;

        double score = AimAIModel.predict(x);
        this.latestScore = score;
        this.latestFeatures = new AIFeatures(score, gcdAnomaly, attackSnap, snapBack, centerLock, roboticSmoothness, linearTracking, switchSnap, pitchFreeze, n);

        // Score threshold & violation buffer
        if (score >= 0.85) {
            scoreBuffer += (score - 0.70) * 2.0;
            if (scoreBuffer >= 3.0) {
                flag(V_AI.write(verbose())
                        .f64(score)
                        .f64(gcdAnomaly)
                        .f64(attackSnap)
                        .f64(centerLock)
                        .f64(roboticSmoothness));
            }
        } else if (score < 0.50) {
            // Rapid decay on legitimate ticks
            scoreBuffer = Math.max(0.0, scoreBuffer - 0.40);
        }
    }

    public double getLatestScore() {
        return latestScore;
    }

    public AIFeatures getLatestFeatures() {
        return latestFeatures;
    }

    public void resetAI() {
        samples.clear();
        scoreBuffer = 0.0;
        latestScore = 0.0;
        lastTargetEntityId = -1;
    }

    public void resetML() {
        resetAI();
    }
}
