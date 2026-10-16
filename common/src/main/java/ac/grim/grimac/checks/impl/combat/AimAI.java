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
import ac.grim.grimac.utils.math.GrimMath;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

import java.util.ArrayDeque;
import java.util.Deque;

@CheckData(name = "AimAI", alternativeName = "CombatAI", stableKey = "grim.combat.aimai", description = "AI neural network scoring combat aim and rotation patterns", experimental = true)
public class AimAI extends Check implements RotationListener, PacketReceiveListener {

    private static final Verbose V_AI = Verbose.of("score={f64:%.2f}, silent={f64:%.2f}, lock={f64:%.2f}, snap={f64:%.2f}, robot={f64:%.2f}");

    private static final int WINDOW_SIZE = 20;
    private static final int MIN_SAMPLES = 4;

    public record AIFeatures(
            double score,
            double impulseSnap,
            double snapBack,
            double continuousLock,
            double roboticSmoothness,
            double switchSnap,
            double pitchFreeze,
            double linearTracking,
            double gcdAnomaly,
            int sampleCount
    ) {}

    private record TargetData(double angle, double yawError, double targetX, double targetZ) {}

    private record Sample(
            float deltaYaw,
            float deltaPitch,
            float rawYaw,
            float rawPitch,
            double angleToTarget,
            double yawError,
            double targetX,
            double targetZ,
            double playerX,
            double playerZ,
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

        float deltaYaw = update.deltaYawABS();
        float deltaPitch = update.deltaPitchABS();

        boolean isSignFlip = (update.deltaYaw() * prevDeltaYaw < 0) || (update.deltaPitch() * prevDeltaPitch < 0);
        double currentAccel = Math.abs(deltaYaw - prevDeltaYaw);
        double jerk = Math.abs(currentAccel - prevJerk);

        double gcdError = calculateGcdError(deltaYaw, deltaPitch);
        TargetData targetData = calculateTargetData(update.newYaw(), update.newPitch());

        samples.addLast(new Sample(
                deltaYaw,
                deltaPitch,
                update.newYaw(),
                update.newPitch(),
                targetData.angle(),
                targetData.yawError(),
                targetData.targetX(),
                targetData.targetZ(),
                player.x,
                player.z,
                gcdError,
                isSignFlip,
                jerk,
                attackedThisTick,
                lastTargetEntityId
        ));
        if (samples.size() > WINDOW_SIZE) {
            samples.removeFirst();
        }

        prevDeltaYaw = update.deltaYaw();
        prevDeltaPitch = update.deltaPitch();
        prevJerk = currentAccel;
        attackedThisTick = false;

        final long now = System.currentTimeMillis();
        final boolean inCombat = (now - lastAttackTime) <= 1500L;

        if (!inCombat) {
            if (scoreBuffer > 0.0) {
                scoreBuffer = Math.max(0.0, scoreBuffer - 0.25);
            }
            return;
        }

        if (samples.size() >= MIN_SAMPLES) {
            evaluateModel();
        }
    }

    private double calculateGcdError(float deltaYaw, float deltaPitch) {
        AimProcessor aimProcessor = player.checkManager.get(AimProcessor.class);
        if (aimProcessor == null) return -1.0;

        double divisor = aimProcessor.modeYaw;
        if (divisor <= GrimMath.MINIMUM_DIVISOR) {
            divisor = aimProcessor.modePitch;
        }

        if (divisor > GrimMath.MINIMUM_DIVISOR && deltaYaw >= 0.5f && deltaYaw <= 2.5f) {
            double dots = deltaYaw / divisor;
            return Math.abs(dots - Math.round(dots));
        }
        return -1.0;
    }

    private TargetData calculateTargetData(float currentYaw, float currentPitch) {
        if (lastTargetEntityId == -1) return new TargetData(15.0, 15.0, 0.0, 0.0);

        PacketEntity target = player.compensatedEntities.entityMap.get(lastTargetEntityId);
        if (target == null || target.isDead) return new TargetData(15.0, 15.0, 0.0, 0.0);

        SimpleCollisionBox box = target.getPossibleCollisionBoxes();
        if (box == null) return new TargetData(15.0, 15.0, 0.0, 0.0);

        double centerX = (box.minX + box.maxX) / 2.0;
        double centerZ = (box.minZ + box.maxZ) / 2.0;
        double eyeY = player.y + player.getEyeHeight();

        double dx = centerX - player.x;
        double dz = centerZ - player.z;
        double bearingYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double yawError = Math.abs(wrapDegrees(currentYaw - bearingYaw));

        double yawRad = Math.toRadians(currentYaw);
        double pitchRad = Math.toRadians(currentPitch);
        double lookX = -Math.cos(pitchRad) * Math.sin(yawRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(pitchRad) * Math.cos(yawRad);

        double height = box.maxY - box.minY;
        double[] targetHeights = {
                box.minY + height * 0.5,
                box.minY + height * 0.85,
                box.minY + height * 0.2
        };

        double minAngle = 180.0;
        for (double ty : targetHeights) {
            double dy = ty - eyeY;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.1) return new TargetData(0.0, yawError, centerX, centerZ);

            double dot = (lookX * dx + lookY * dy + lookZ * dz) / len;
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            if (angle < minAngle) {
                minAngle = angle;
            }
        }

        return new TargetData(minAngle, yawError, centerX, centerZ);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped > 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private void evaluateModel() {
        int n = samples.size();
        if (n < MIN_SAMPLES) return;

        Sample[] sampleArr = samples.toArray(new Sample[0]);

        int attackCount = 0;
        for (Sample s : sampleArr) {
            if (s.attacked()) attackCount++;
        }
        if (attackCount < 1) {
            scoreBuffer = Math.max(0.0, scoreBuffer - 0.25);
            return;
        }

        double sumAngle = 0.0;
        int trackedCount = 0;
        for (Sample s : sampleArr) {
            if (s.angleToTarget() < 6.0) {
                sumAngle += s.angleToTarget();
                trackedCount++;
            }
        }

        double impulseSnap = 0.0;
        int impulseCount = 0;
        for (int i = 1; i < sampleArr.length - 1; i++) {
            Sample prev = sampleArr[i - 1];
            Sample curr = sampleArr[i];
            Sample next = sampleArr[i + 1];

            boolean snapOnCurr = curr.attacked() && curr.deltaYaw() > 8.0f && curr.yawError() < 1.5 && prev.yawError() > 7.0;
            boolean snapOnPrev = curr.attacked() && prev.deltaYaw() > 8.0f && curr.yawError() < 1.5 && (i >= 2 ? sampleArr[i - 2].yawError() > 7.0 : prev.yawError() > 7.0);

            if (snapOnCurr) {
                boolean humanFlick = prev.deltaYaw() >= 4.0f || (next.deltaYaw() >= 3.0f && next.yawError() > 2.0);
                if (!humanFlick) {
                    impulseCount++;
                }
            } else if (snapOnPrev) {
                Sample prev2 = i >= 2 ? sampleArr[i - 2] : null;
                boolean humanFlick = (prev2 != null && prev2.deltaYaw() >= 4.0f) || (curr.deltaYaw() >= 3.0f && curr.yawError() > 2.0);
                if (!humanFlick) {
                    impulseCount++;
                }
            }
        }
        if (impulseCount >= 1) {
            impulseSnap = Math.min(1.0, 0.75 + impulseCount * 0.20);
        }

        double snapBack = 0.0;
        int silentCount = 0;
        for (int i = 1; i < sampleArr.length - 1; i++) {
            Sample prev = sampleArr[i - 1];
            Sample curr = sampleArr[i];
            Sample next = sampleArr[i + 1];
            if (curr.attacked() && (curr.deltaYaw() > 7.0f || prev.deltaYaw() > 7.0f) && curr.yawError() < 2.0) {
                if (next.isSignFlip() && next.deltaYaw() > 6.0f) {
                    float returnDiff = Math.abs((next.rawYaw() - prev.rawYaw()) % 360.0f);
                    if (returnDiff > 180.0f) returnDiff = 360.0f - returnDiff;
                    if (returnDiff < 10.0f) {
                        silentCount++;
                    }
                }
            }
        }
        if (silentCount >= 1) {
            snapBack = Math.min(1.0, 0.80 + silentCount * 0.20);
        }

        double continuousLock = 0.0;
        int attackHits = 0;
        double maxYawError = 0.0;
        double sumYawError = 0.0;
        for (Sample s : sampleArr) {
            if (s.attacked() && s.targetEntityId() != -1) {
                attackHits++;
                double err = s.yawError();
                sumYawError += err;
                if (err > maxYawError) {
                    maxYawError = err;
                }
            }
        }
        if (attackHits >= 2) {
            if (maxYawError < 1.2) {
                continuousLock = 0.95;
            } else if (maxYawError < 1.8 && (sumYawError / attackHits) < 1.2) {
                continuousLock = 0.75;
            }
        }

        int constantSpeedCount = 0;
        for (int i = 1; i < sampleArr.length; i++) {
            float d1 = sampleArr[i].deltaYaw();
            float d0 = sampleArr[i - 1].deltaYaw();
            if (d1 > 2.0f && Math.abs(d1 - d0) < 0.02f && sampleArr[i].angleToTarget() < 8.0) {
                constantSpeedCount++;
            }
        }
        double roboticSmoothness = 0.0;
        if (constantSpeedCount >= 2 && trackedCount >= 4) {
            roboticSmoothness = Math.min(1.0, constantSpeedCount * 0.45);
        }

        double switchSnap = 0.0;
        int switchCount = 0;
        for (int i = 0; i < sampleArr.length - 1; i++) {
            Sample s1 = sampleArr[i];
            Sample s2 = sampleArr[i + 1];
            if (s1.attacked() && s2.attacked()
                    && s1.targetEntityId() != -1 && s2.targetEntityId() != -1
                    && s1.targetEntityId() != s2.targetEntityId()
                    && s2.deltaYaw() > 15.0f && s2.yawError() < 2.0) {
                switchCount++;
            }
        }
        if (switchCount >= 1) {
            switchSnap = Math.min(1.0, 0.80 + switchCount * 0.20);
        }

        double sumYaw = 0;
        for (Sample s : sampleArr) sumYaw += s.deltaYaw();
        double meanYaw = sumYaw / n;

        double pitchFreeze = 0.0;
        if (trackedCount >= 6 && meanYaw > 4.0) {
            float maxPitch = 0.0f;
            for (Sample s : sampleArr) {
                if (s.deltaPitch() > maxPitch) maxPitch = s.deltaPitch();
            }
            if (maxPitch < 0.0001f) {
                pitchFreeze = 0.85;
            }
        }

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
        if (trackedCount >= 6 && stdYaw > 1.2 && stdPitch > 1.2 && linearity > 0.999 && (sumYaw + sumPitch) > 20.0) {
            linearTracking = 0.80;
        }

        double gcdAnomaly = 0.0;
        AimProcessor aimProcessor = player.checkManager.get(AimProcessor.class);
        if (aimProcessor != null && aimProcessor.modeYaw > 0.005) {
            int gcdEvaluated = 0;
            int gcdViolations = 0;
            for (Sample s : sampleArr) {
                if (s.deltaYaw() >= 0.5f && s.deltaYaw() <= 2.5f && s.gcdError() >= 0.0) {
                    gcdEvaluated++;
                    if (s.gcdError() > 0.20) {
                        gcdViolations++;
                    }
                }
            }
            if (gcdEvaluated >= 6) {
                gcdAnomaly = (double) gcdViolations / gcdEvaluated;
            }
        }

        double[] x = new double[AimAIModel.INPUT_SIZE];
        x[0] = impulseSnap;
        x[1] = snapBack;
        x[2] = continuousLock;
        x[3] = roboticSmoothness;
        x[4] = switchSnap;
        x[5] = pitchFreeze;
        x[6] = linearTracking;
        x[7] = gcdAnomaly;

        double score = AimAIModel.predict(x);
        this.latestScore = score;
        this.latestFeatures = new AIFeatures(score, impulseSnap, snapBack, continuousLock, roboticSmoothness, switchSnap, pitchFreeze, linearTracking, gcdAnomaly, n);

        boolean justFinishedAttack = (sampleArr.length >= 2) && sampleArr[sampleArr.length - 2].attacked();

        if (justFinishedAttack) {
            if (score >= 0.50) {
                scoreBuffer += (score - 0.30) * 3.5;
                if (scoreBuffer >= 1.2) {
                    flag(V_AI.write(verbose())
                            .f64(score)
                            .f64(snapBack)
                            .f64(continuousLock)
                            .f64(impulseSnap)
                            .f64(roboticSmoothness));
                    scoreBuffer = Math.min(scoreBuffer, 3.0);
                }
            } else if (score < 0.25) {
                scoreBuffer = Math.max(0.0, scoreBuffer - 0.40);
            }
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
