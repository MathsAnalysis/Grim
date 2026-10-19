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

    public record Sample(
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
            boolean isYawSignFlip,
            boolean isPitchSignFlip,
            double jerk,
            boolean attacked,
            int targetEntityId
    ) {
        public boolean isSignFlip() {
            return isYawSignFlip || isPitchSignFlip;
        }
    }

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

        boolean isYawSignFlip = (update.deltaYaw() * prevDeltaYaw < 0);
        boolean isPitchSignFlip = (update.deltaPitch() * prevDeltaPitch < 0);
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
                isYawSignFlip,
                isPitchSignFlip,
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

        AimProcessor aimProcessor = player.checkManager.get(AimProcessor.class);
        AIFeatures features = computeFeatures(sampleArr, aimProcessor);

        this.latestScore = features.score();
        this.latestFeatures = features;

        boolean justFinishedAttack = (sampleArr.length >= 2) && sampleArr[sampleArr.length - 2].attacked();

        if (justFinishedAttack) {
            boolean activeVertical = isVerticalMovementActive(sampleArr);
            int strongCount = countStrongIndicators(features);
            scoreBuffer = updateBuffer(scoreBuffer, features.score(), true, activeVertical, strongCount);

            if (scoreBuffer >= 2.5) {
                flag(V_AI.write(verbose())
                        .f64(features.score())
                        .f64(features.snapBack())
                        .f64(features.continuousLock())
                        .f64(features.impulseSnap())
                        .f64(features.roboticSmoothness()));
                scoreBuffer = Math.min(scoreBuffer, 4.0);
            }
        }
    }

    public static AIFeatures computeFeatures(Sample[] sampleArr, AimProcessor aimProcessor) {
        double divisor = 0.0;
        if (aimProcessor != null) {
            divisor = aimProcessor.modeYaw;
            if (divisor <= GrimMath.MINIMUM_DIVISOR) {
                divisor = aimProcessor.modePitch;
            }
        }
        return computeFeatures(sampleArr, divisor);
    }

    public static AIFeatures computeFeatures(Sample[] sampleArr, double divisor) {
        int n = (sampleArr != null) ? sampleArr.length : 0;
        if (n < MIN_SAMPLES) {
            return new AIFeatures(0.0, 0, 0, 0, 0, 0, 0, 0, 0, n);
        }

        int pitchFlips = 0;
        float maxPitchDelta = 0.0f;
        double sumPitchDelta = 0.0;
        for (Sample s : sampleArr) {
            float dp = s.deltaPitch();
            sumPitchDelta += dp;
            if (dp > maxPitchDelta) maxPitchDelta = dp;
            if (s.isPitchSignFlip()) pitchFlips++;
        }
        double meanDeltaPitch = sumPitchDelta / n;

        double sumSqPitch = 0.0;
        for (Sample s : sampleArr) {
            double diff = s.deltaPitch() - meanDeltaPitch;
            sumSqPitch += diff * diff;
        }
        double stdDeltaPitch = Math.sqrt(sumSqPitch / n);

        boolean activeVerticalMovement = (pitchFlips >= 2 && maxPitchDelta > 3.5f)
                || (stdDeltaPitch > 2.0 && meanDeltaPitch > 1.5)
                || (meanDeltaPitch > 2.0 && pitchFlips == 0 && maxPitchDelta > 3.5f)
                || (maxPitchDelta > 8.0f);

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

            if (!curr.attacked()) continue;

            if (activeVerticalMovement || curr.deltaPitch() > 4.0f || prev.deltaPitch() > 4.0f || next.deltaPitch() > 4.0f) {
                continue;
            }

            boolean snapOnCurr = curr.deltaYaw() > 14.0f
                    && curr.yawError() < 1.2 && curr.angleToTarget() < 1.5
                    && (prev.yawError() > 8.0 || prev.angleToTarget() > 10.0);

            Sample prev2 = (i >= 2) ? sampleArr[i - 2] : null;
            boolean snapOnPrev = prev.deltaYaw() > 14.0f
                    && curr.yawError() < 1.2 && curr.angleToTarget() < 1.5
                    && (prev2 != null ? (prev2.yawError() > 8.0 || prev2.angleToTarget() > 10.0) : prev.yawError() > 8.0);

            if (snapOnCurr) {
                boolean humanPreMovement = prev.deltaYaw() >= 4.0f;
                boolean humanPostMovement = next.deltaYaw() >= 3.0f;
                if (!humanPreMovement && !humanPostMovement && curr.jerk() > 8.0) {
                    impulseCount++;
                }
            } else if (snapOnPrev) {
                boolean humanPreMovement = (prev2 != null && prev2.deltaYaw() >= 4.0f);
                boolean humanPostMovement = curr.deltaYaw() >= 3.0f;
                if (!humanPreMovement && !humanPostMovement) {
                    impulseCount++;
                }
            }
        }
        if (impulseCount >= 2) {
            impulseSnap = Math.min(1.0, 0.75 + (impulseCount - 1) * 0.20);
        } else if (impulseCount == 1) {
            impulseSnap = 0.40;
        }

        double snapBack = 0.0;
        int silentCount = 0;
        for (int i = 1; i < sampleArr.length - 1; i++) {
            Sample prev = sampleArr[i - 1];
            Sample curr = sampleArr[i];
            Sample next = sampleArr[i + 1];

            if (!curr.attacked()) continue;

            boolean yawSnappedOntoTarget = (curr.deltaYaw() > 8.0f || prev.deltaYaw() > 8.0f)
                    && curr.yawError() < 1.5 && curr.angleToTarget() < 3.0
                    && (prev.yawError() > 4.0 || prev.angleToTarget() > 5.0);

            boolean yawReversed = next.isYawSignFlip() && next.deltaYaw() > 6.0f;

            float returnDiffYaw = Math.abs((next.rawYaw() - prev.rawYaw()) % 360.0f);
            if (returnDiffYaw > 180.0f) returnDiffYaw = 360.0f - returnDiffYaw;
            boolean returnedYaw = returnDiffYaw < 6.0f && next.yawError() > 2.5;

            boolean verticalInterference = activeVerticalMovement
                    || curr.deltaPitch() > 5.0f || prev.deltaPitch() > 5.0f || next.deltaPitch() > 5.0f;

            if (yawSnappedOntoTarget && yawReversed && returnedYaw && !verticalInterference) {
                silentCount++;
                continue;
            }

            boolean pitchSnappedOntoTarget = (curr.deltaPitch() > 8.0f || prev.deltaPitch() > 8.0f)
                    && curr.angleToTarget() < 2.5
                    && (prev.angleToTarget() > 6.0);
            boolean pitchReversed = next.isPitchSignFlip() && next.deltaPitch() > 6.0f;
            float returnDiffPitch = Math.abs(next.rawPitch() - prev.rawPitch());
            boolean returnedPitch = returnDiffPitch < 5.0f && next.angleToTarget() > 3.0;

            if (pitchSnappedOntoTarget && pitchReversed && returnedPitch) {
                silentCount++;
            }
        }
        if (silentCount >= 2) {
            snapBack = Math.min(1.0, 0.75 + (silentCount - 1) * 0.20);
        } else if (silentCount == 1) {
            snapBack = 0.50;
        }

        double continuousLock = 0.0;
        if (!activeVerticalMovement && stdDeltaPitch < 1.5) {
            int currentStreak = 0;
            int maxStreak = 0;
            int streakStartIndex = -1;
            int bestStreakStart = -1;

            for (int i = 0; i < sampleArr.length; i++) {
                Sample s = sampleArr[i];
                if (s.angleToTarget() < 3.0 && s.yawError() < 2.0) {
                    if (currentStreak == 0) streakStartIndex = i;
                    currentStreak++;
                    if (currentStreak > maxStreak) {
                        maxStreak = currentStreak;
                        bestStreakStart = streakStartIndex;
                    }
                } else {
                    currentStreak = 0;
                }
            }

            int attackHitsInStreak = 0;
            if (maxStreak >= 6 && bestStreakStart >= 0) {
                int streakEnd = Math.min(sampleArr.length, bestStreakStart + maxStreak + 1);
                int start = Math.max(0, bestStreakStart - 1);
                for (int i = start; i < streakEnd; i++) {
                    if (sampleArr[i].attacked() && sampleArr[i].targetEntityId() != -1) {
                        attackHitsInStreak++;
                    }
                }

                double sumStreakAngle = 0.0;
                for (int i = bestStreakStart; i < bestStreakStart + maxStreak; i++) {
                    sumStreakAngle += sampleArr[i].angleToTarget();
                }
                double meanStreakAngle = sumStreakAngle / maxStreak;
                double sumStreakVar = 0.0;
                for (int i = bestStreakStart; i < bestStreakStart + maxStreak; i++) {
                    double d = sampleArr[i].angleToTarget() - meanStreakAngle;
                    sumStreakVar += d * d;
                }
                double streakAngleVar = sumStreakVar / maxStreak;

                double totalMovement = 0.0;
                for (int i = 1; i < sampleArr.length; i++) {
                    totalMovement += sampleArr[i].deltaYaw() + sampleArr[i].deltaPitch();
                }

                if (maxStreak >= 8 && attackHitsInStreak >= 2 && streakAngleVar < 0.20 && totalMovement > 5.0) {
                    continuousLock = 0.90;
                } else if (maxStreak >= 6 && attackHitsInStreak >= 2 && streakAngleVar < 0.35 && totalMovement > 4.0) {
                    continuousLock = 0.70;
                }
            }
        }

        double roboticSmoothness = 0.0;
        if (!activeVerticalMovement && stdDeltaPitch < 1.0) {
            int consecutiveConstant = 0;
            int maxConsecutiveConstant = 0;
            for (int i = 1; i < sampleArr.length; i++) {
                float d1 = sampleArr[i].deltaYaw();
                float d0 = sampleArr[i - 1].deltaYaw();
                if (d1 > 2.0f && Math.abs(d1 - d0) < 0.015f && sampleArr[i].angleToTarget() < 6.0 && sampleArr[i].deltaPitch() < 0.4f) {
                    consecutiveConstant++;
                    if (consecutiveConstant > maxConsecutiveConstant) {
                        maxConsecutiveConstant = consecutiveConstant;
                    }
                } else {
                    consecutiveConstant = 0;
                }
            }
            if (maxConsecutiveConstant >= 5) {
                roboticSmoothness = 0.85;
            } else if (maxConsecutiveConstant >= 4) {
                roboticSmoothness = 0.60;
            }
        }

        double switchSnap = 0.0;
        int switchCount = 0;
        for (int i = 0; i < sampleArr.length - 1; i++) {
            Sample s1 = sampleArr[i];
            Sample s2 = sampleArr[i + 1];
            if (s1.attacked() && s2.attacked()
                    && s1.targetEntityId() != -1 && s2.targetEntityId() != -1
                    && s1.targetEntityId() != s2.targetEntityId()
                    && s2.deltaYaw() > 16.0f && s2.yawError() < 1.5 && s2.angleToTarget() < 2.5) {
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
            if (maxPitchDelta < 0.0001f) {
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
        if (!activeVerticalMovement && trackedCount >= 8 && stdYaw > 1.2 && stdPitch > 1.2 && linearity > 0.9995 && (sumYaw + sumPitch) > 25.0) {
            linearTracking = 0.60;
        }

        double gcdAnomaly = 0.0;
        if (divisor > 0.005) {
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

        double rawModelScore = AimAIModel.predict(x);

        int strongCount = 0;
        if (impulseSnap >= 0.70) strongCount++;
        if (snapBack >= 0.70) strongCount++;
        if (continuousLock >= 0.70) strongCount++;
        if (roboticSmoothness >= 0.70) strongCount++;
        if (switchSnap >= 0.70) strongCount++;
        if (pitchFreeze >= 0.70) strongCount++;
        if (gcdAnomaly >= 0.40) strongCount++;

        int moderateCount = 0;
        if (impulseSnap >= 0.35) moderateCount++;
        if (snapBack >= 0.35) moderateCount++;
        if (continuousLock >= 0.35) moderateCount++;
        if (roboticSmoothness >= 0.35) moderateCount++;
        if (switchSnap >= 0.35) moderateCount++;
        if (pitchFreeze >= 0.35) moderateCount++;
        if (linearTracking >= 0.50) moderateCount++;
        if (gcdAnomaly >= 0.20) moderateCount++;

        double finalScore;
        if (strongCount >= 2 || (strongCount >= 1 && moderateCount >= 2)) {
            finalScore = rawModelScore;
        } else if (strongCount == 1 || moderateCount >= 2) {
            finalScore = Math.min(rawModelScore, 0.45);
        } else if (moderateCount == 1) {
            finalScore = Math.min(rawModelScore, 0.25);
        } else {
            finalScore = Math.min(rawModelScore, 0.10);
        }

        return new AIFeatures(
                finalScore,
                impulseSnap,
                snapBack,
                continuousLock,
                roboticSmoothness,
                switchSnap,
                pitchFreeze,
                linearTracking,
                gcdAnomaly,
                n
        );
    }

    public static boolean isVerticalMovementActive(Sample[] sampleArr) {
        if (sampleArr == null || sampleArr.length < 2) return false;
        int pitchFlips = 0;
        float maxPitchDelta = 0.0f;
        double sumPitchDelta = 0.0;
        for (Sample s : sampleArr) {
            float dp = s.deltaPitch();
            sumPitchDelta += dp;
            if (dp > maxPitchDelta) maxPitchDelta = dp;
            if (s.isPitchSignFlip()) pitchFlips++;
        }
        double meanDeltaPitch = sumPitchDelta / sampleArr.length;

        double sumSqPitch = 0.0;
        for (Sample s : sampleArr) {
            double diff = s.deltaPitch() - meanDeltaPitch;
            sumSqPitch += diff * diff;
        }
        double stdDeltaPitch = Math.sqrt(sumSqPitch / sampleArr.length);

        return (pitchFlips >= 2 && maxPitchDelta > 3.5f)
                || (stdDeltaPitch > 2.0 && meanDeltaPitch > 1.5)
                || (meanDeltaPitch > 2.0 && pitchFlips == 0 && maxPitchDelta > 3.5f)
                || (maxPitchDelta > 8.0f);
    }

    public static int countStrongIndicators(AIFeatures f) {
        int count = 0;
        if (f.impulseSnap() >= 0.70) count++;
        if (f.snapBack() >= 0.70) count++;
        if (f.continuousLock() >= 0.70) count++;
        if (f.roboticSmoothness() >= 0.70) count++;
        if (f.switchSnap() >= 0.70) count++;
        if (f.pitchFreeze() >= 0.70) count++;
        if (f.gcdAnomaly() >= 0.40) count++;
        return count;
    }

    public static int countModerateIndicators(AIFeatures f) {
        int count = 0;
        if (f.impulseSnap() >= 0.35) count++;
        if (f.snapBack() >= 0.35) count++;
        if (f.continuousLock() >= 0.35) count++;
        if (f.roboticSmoothness() >= 0.35) count++;
        if (f.switchSnap() >= 0.35) count++;
        if (f.pitchFreeze() >= 0.35) count++;
        if (f.linearTracking() >= 0.50) count++;
        if (f.gcdAnomaly() >= 0.20) count++;
        return count;
    }

    public static double updateBuffer(double currentBuffer, double score, boolean justFinishedAttack, boolean activeVertical, int strongCount) {
        if (!justFinishedAttack) return currentBuffer;
        if (score >= 0.60) {
            double scoreDelta = (score - 0.40);
            double multiplier = (strongCount >= 2) ? 2.0 : 1.2;
            return Math.min(4.0, currentBuffer + scoreDelta * multiplier);
        } else if (score < 0.35) {
            double decayAmount = activeVertical ? 0.75 : 0.40;
            return Math.max(0.0, currentBuffer - decayAmount);
        }
        return currentBuffer;
    }

    public double getScoreBuffer() {
        return scoreBuffer;
    }

    public int getSampleCount() {
        return samples.size();
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
