package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.impl.combat.AimAI.AIFeatures;
import ac.grim.grimac.checks.impl.combat.AimAI.Sample;
import ac.grim.grimac.checks.impl.combat.ai.AimAIModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AimAITest {

    static {
        System.setProperty("GrimPlatformOverride", "BUKKIT");
    }

    private static final int TARGET_ID = 42;
    private static final int OTHER_TARGET_ID = 99;

    /**
     * Helper to construct a Sample with common defaults.
     */
    private static Sample sample(
            float deltaYaw,
            float deltaPitch,
            float rawYaw,
            float rawPitch,
            double angleToTarget,
            double yawError,
            boolean isYawSignFlip,
            boolean isPitchSignFlip,
            double jerk,
            boolean attacked,
            int targetEntityId
    ) {
        return new Sample(
                deltaYaw,
                deltaPitch,
                rawYaw,
                rawPitch,
                angleToTarget,
                yawError,
                0.0,
                3.0,
                0.0,
                0.0,
                0.0,
                isYawSignFlip,
                isPitchSignFlip,
                jerk,
                attacked,
                targetEntityId
        );
    }

    /**
     * Overload specifying gcdError.
     */
    private static Sample sampleWithGcd(
            float deltaYaw,
            float deltaPitch,
            float rawYaw,
            float rawPitch,
            double angleToTarget,
            double yawError,
            double gcdError,
            boolean isYawSignFlip,
            boolean isPitchSignFlip,
            double jerk,
            boolean attacked,
            int targetEntityId
    ) {
        return new Sample(
                deltaYaw,
                deltaPitch,
                rawYaw,
                rawPitch,
                angleToTarget,
                yawError,
                0.0,
                3.0,
                0.0,
                0.0,
                gcdError,
                isYawSignFlip,
                isPitchSignFlip,
                jerk,
                attacked,
                targetEntityId
        );
    }

    @Test
    @DisplayName("Rapid vertical camera movement (up and down) while hitting an opponent must not trigger a false flag")
    void testRapidVerticalCameraMovement_NoFalsePositive() {
        // Player moves camera up and down across 20 ticks (nodding/shaking pitch, pitch flips frequently)
        // Yaw remains roughly directed near the target (yawError ~ 0.8 - 1.4), and an attack occurs on tick 10.
        List<Sample> samples = new ArrayList<>();
        float currentPitch = -20.0f;
        float currentYaw = 100.0f;
        float prevDeltaPitch = 0.0f;

        for (int i = 0; i < 20; i++) {
            // Pitch moves between -20 and +25 degrees, reversing every 3-4 ticks
            float pitchDir = (i / 4) % 2 == 0 ? 8.0f : -8.0f;
            float deltaPitch = Math.abs(pitchDir);
            currentPitch += pitchDir;
            boolean pitchFlip = (pitchDir * prevDeltaPitch < 0);
            prevDeltaPitch = pitchDir;

            // Small horizontal drifting
            float deltaYaw = 1.0f + (float) (Math.sin(i) * 0.5f);
            currentYaw += (i % 2 == 0 ? deltaYaw : -deltaYaw);

            // Accidental hit at tick 10
            boolean attacked = (i == 10);
            double angleToTarget = Math.abs(currentPitch); // 3D angle varies significantly with pitch!
            double yawError = 1.0; // horizontal angle is close

            samples.add(sample(
                    deltaYaw,
                    deltaPitch,
                    currentYaw,
                    currentPitch,
                    angleToTarget,
                    yawError,
                    false,
                    pitchFlip,
                    1.0,
                    attacked,
                    TARGET_ID
            ));
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        assertTrue(AimAI.isVerticalMovementActive(sampleArr), "Active vertical movement should be recognized");

        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        // Verification of all individual detection rules
        assertEquals(0.0, features.snapBack(), 1e-4, "Snapback must be 0 for pure vertical camera movement");
        assertEquals(0.0, features.continuousLock(), 1e-4, "Continuous lock must be 0 during vertical camera movement");
        assertEquals(0.0, features.impulseSnap(), 1e-4, "Impulse snap must be 0 during vertical camera movement");
        assertEquals(0.0, features.roboticSmoothness(), 1e-4, "Robotic smoothness must be 0 during vertical movement");
        assertEquals(0.0, features.pitchFreeze(), 1e-4, "Pitch freeze must be 0 when pitch is actively changing");

        assertTrue(features.score() < 0.20, "Model score must be very low for legitimate vertical camera movement (got " + features.score() + ")");

        // Buffer must decay, not accumulate
        double buffer = 0.5;
        buffer = AimAI.updateBuffer(buffer, features.score(), true, true, 0);
        assertEquals(0.0, buffer, 1e-4, "Buffer should decay to 0 on vertical movement hit");
    }

    @Test
    @DisplayName("Human tracking of a moving player with natural hand variance must not flag")
    void testTrackingMovingPlayer_HumanVariance_NoFalsePositive() {
        // Player smoothly tracks an opponent moving across the field.
        // Human tracking has natural angle variance, non-constant velocity, and micro-corrections.
        List<Sample> samples = new ArrayList<>();
        float yaw = 50.0f;
        float pitch = 10.0f;

        for (int i = 0; i < 20; i++) {
            // Human delta yaw fluctuates naturally between 2.0 and 4.5 degrees
            float deltaYaw = 2.5f + (float) (Math.cos(i * 0.7) * 1.2f);
            float deltaPitch = 0.8f + (float) (Math.sin(i * 0.5) * 0.5f);
            yaw += deltaYaw;
            pitch += deltaPitch;

            // Human angle to target fluctuates naturally around 1.0 to 2.8 degrees (variance > 0.4)
            double angle = 1.5 + Math.sin(i * 1.1) * 0.9;
            double yawErr = 1.0 + Math.cos(i * 0.9) * 0.6;

            boolean attack = (i == 6 || i == 14); // Two normal hits while tracking

            samples.add(sample(
                    deltaYaw,
                    deltaPitch,
                    yaw,
                    pitch,
                    angle,
                    yawErr,
                    false,
                    false,
                    1.2,
                    attack,
                    TARGET_ID
            ));
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        assertEquals(0.0, features.continuousLock(), 1e-4, "Natural tracking variance should not trigger continuous lock");
        assertEquals(0.0, features.roboticSmoothness(), 1e-4, "Fluctuating human speed should not trigger robotic smoothness");
        assertEquals(0.0, features.snapBack(), 1e-4);
        assertEquals(0.0, features.impulseSnap(), 1e-4);
        assertTrue(features.score() < 0.25, "Human tracking score should remain low (got " + features.score() + ")");
    }

    @Test
    @DisplayName("Recoil compensation (pulling down vertically) must not trigger false aimbot flags")
    void testRecoilCompensation_NoFalsePositive() {
        // Player attacks while pulling down mouse vertically (e.g. deltaPitch ~ 3-5 degrees, deltaYaw ~ 0.5-1.5 degrees)
        List<Sample> samples = new ArrayList<>();
        float pitch = 0.0f;
        float yaw = 90.0f;

        for (int i = 0; i < 20; i++) {
            float deltaPitch = 3.5f + (float) (Math.sin(i) * 0.8f);
            float deltaYaw = 1.0f + (float) (Math.cos(i) * 0.4f);
            pitch += deltaPitch;
            yaw += deltaYaw;

            boolean attack = (i == 5 || i == 12);
            double angle = 2.0 + Math.abs(Math.sin(i));
            double yawErr = 1.2;

            samples.add(sample(
                    deltaYaw,
                    deltaPitch,
                    yaw,
                    pitch,
                    angle,
                    yawErr,
                    false,
                    false,
                    1.0,
                    attack,
                    TARGET_ID
            ));
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        assertTrue(AimAI.isVerticalMovementActive(sampleArr), "Recoil control should register as vertical movement");

        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);
        assertEquals(0.0, features.snapBack(), 1e-4);
        assertEquals(0.0, features.continuousLock(), 1e-4);
        assertEquals(0.0, features.impulseSnap(), 1e-4);
        assertEquals(0.0, features.linearTracking(), 1e-4, "Recoil movement must not trigger robotic linear tracking");
        assertTrue(features.score() < 0.20, "Recoil compensation must not score as aimbot");
    }

    @Test
    @DisplayName("Legitimate human flick onto target and stopping on target must not flag")
    void testLegitimateFlickAndStop_NoFalsePositive() {
        // A skilled player turns from 16 degrees off-target, flicks onto target, lands accurately, and stops on target to attack.
        List<Sample> samples = new ArrayList<>();

        // Ticks 0-8: looking away
        for (int i = 0; i < 9; i++) {
            samples.add(sample(1.0f, 0.5f, 50.0f + i, 10.0f, 15.0, 15.0, false, false, 0.2, false, TARGET_ID));
        }

        // Tick 9 (prev): initial flick movement
        samples.add(sample(5.0f, 1.0f, 65.0f, 10.0f, 10.0, 10.0, false, false, 2.0, false, TARGET_ID));

        // Tick 10 (curr): flick lands on target and attacks
        samples.add(sample(15.0f, 1.0f, 80.0f, 10.0f, 1.0, 0.8, false, false, 4.0, true, TARGET_ID));

        // Tick 11 (next): human lands on target, does not overshoot (yawError stays low)
        samples.add(sample(1.5f, 0.5f, 81.0f, 10.0f, 1.2, 0.9, false, false, 2.0, false, TARGET_ID));

        // Ticks 12-19: staying on target
        for (int i = 12; i < 20; i++) {
            samples.add(sample(1.0f, 0.5f, 81.0f + (i - 12) * 0.2f, 10.0f, 1.5, 1.0, false, false, 0.2, false, TARGET_ID));
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        // A single human flick with pre-movement should not be treated as a robotic snap
        assertEquals(0.0, features.snapBack(), 1e-4, "Normal flick is not a snap back");
        assertTrue(features.score() < 0.35, "Single legitimate flick must not score above cheat threshold (got " + features.score() + ")");

        // Buffer must not increase
        double buffer = 0.0;
        int strong = AimAI.countStrongIndicators(features);
        buffer = AimAI.updateBuffer(buffer, features.score(), true, false, strong);
        assertEquals(0.0, buffer, 1e-4, "Buffer should remain 0 after legitimate flick");
    }

    @Test
    @DisplayName("Single hit or camera movement can never alone trigger a flag")
    void testSingleHitNeverFlags() {
        // Even if an unusual movement occurs on a single hit, the score buffer accumulation
        // requires multiple consistent detections over time before reaching flag threshold 2.5.
        double buffer = 0.0;

        // Even with a high single-hit score of 0.85 and 2 strong indicators:
        buffer = AimAI.updateBuffer(buffer, 0.85, true, false, 2);
        assertTrue(buffer < 2.5, "Single hit must not reach flag threshold (buffer: " + buffer + ")");
        assertEquals(0.90, buffer, 1e-4);

        // Second hit:
        buffer = AimAI.updateBuffer(buffer, 0.85, true, false, 2);
        assertTrue(buffer < 2.5, "Second hit must not yet reach flag threshold (buffer: " + buffer + ")");
        assertEquals(1.80, buffer, 1e-4);

        // Third hit reaches threshold (over time accumulation):
        buffer = AimAI.updateBuffer(buffer, 0.85, true, false, 2);
        assertTrue(buffer >= 2.5, "Third hit with sustained cheat score should reach flag threshold");
    }

    @Test
    @DisplayName("Genuine silent aimbot (snap-back) is accurately detected and flagged")
    void testGenuineSilentAimbot_DetectedAndFlagged() {
        // Silent aimbot snaps to target on attack tick from off-target, then instantly snaps back
        // to original angle on the very next tick, reversing yaw delta.
        List<Sample> samples = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            if (i == 8) {
                // Pre-attack: facing away
                samples.add(sample(1.0f, 0.2f, 40.0f, 10.0f, 12.0, 12.0, false, false, 0.1, false, TARGET_ID));
            } else if (i == 9) {
                // Attack tick: snaps 12 degrees to target (yaw 52.0)
                samples.add(sample(12.0f, 0.2f, 52.0f, 10.0f, 0.8, 0.6, false, false, 11.0, true, TARGET_ID));
            } else if (i == 10) {
                // Post-attack tick: snaps 11.5 degrees BACK (yaw returns to 40.5, reversing direction)
                samples.add(sample(11.5f, 0.2f, 40.5f, 10.0f, 11.5, 11.5, true, false, 10.0, false, TARGET_ID));
            } else if (i == 15) {
                // Second silent snap in the window
                samples.add(sample(1.0f, 0.2f, 40.0f, 10.0f, 12.0, 12.0, false, false, 0.1, false, TARGET_ID));
            } else if (i == 16) {
                samples.add(sample(12.0f, 0.2f, 52.0f, 10.0f, 0.8, 0.6, false, false, 11.0, true, TARGET_ID));
            } else if (i == 17) {
                samples.add(sample(11.5f, 0.2f, 40.5f, 10.0f, 11.5, 11.5, true, false, 10.0, false, TARGET_ID));
            } else {
                samples.add(sample(1.0f, 0.2f, 40.0f, 10.0f, 12.0, 12.0, false, false, 0.1, false, TARGET_ID));
            }
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        assertTrue(features.snapBack() >= 0.75, "Silent snapback should be detected (got " + features.snapBack() + ")");
    }

    @Test
    @DisplayName("Genuine continuous lock (sticky aimbot) is accurately detected and flagged")
    void testGenuineContinuousLock_StickyAimbot_DetectedAndFlagged() {
        // Sticky aimbot locks onto moving entity across 10 consecutive ticks with near-zero 3D angle variance (< 0.05)
        List<Sample> samples = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            // Target is moving, bot turns smoothly to match perfectly with 0.1 degree tracking error
            float deltaYaw = 3.0f;
            float yaw = 50.0f + i * deltaYaw;
            double angle = 0.3 + (i % 2) * 0.02; // Rigid lock: variance ~ 0.0001
            double yawErr = 0.3;

            boolean attack = (i == 8 || i == 14);
            samples.add(sample(deltaYaw, 0.1f, yaw, 10.0f, angle, yawErr, false, false, 0.01, attack, TARGET_ID));
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        assertTrue(features.continuousLock() >= 0.85, "Rigid continuous lock should be detected (got " + features.continuousLock() + ")");
    }

    @Test
    @DisplayName("Genuine instant robotic snap is accurately detected")
    void testGenuineInstantRoboticSnap_Detected() {
        // Aimbot snaps 20 degrees with zero pre-movement, zero follow-through, landing dead center
        List<Sample> samples = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            if (i == 8) {
                // Tick before snap: dead stop (0.5 delta)
                samples.add(sample(0.5f, 0.1f, 30.0f, 10.0f, 20.0, 20.0, false, false, 0.0, false, TARGET_ID));
            } else if (i == 9) {
                // Snap tick: 20 degrees snap directly onto target, huge jerk, hits target
                samples.add(sample(20.0f, 0.1f, 50.0f, 10.0f, 0.4, 0.3, false, false, 19.5, true, TARGET_ID));
            } else if (i == 10) {
                // Tick after snap: stops dead on target (0.5 delta, 0 follow-through)
                samples.add(sample(0.5f, 0.1f, 50.5f, 10.0f, 0.5, 0.4, false, false, 19.0, false, TARGET_ID));
            } else if (i == 14) {
                samples.add(sample(0.5f, 0.1f, 50.5f, 10.0f, 18.0, 18.0, false, false, 0.0, false, TARGET_ID));
            } else if (i == 15) {
                // Second robotic snap
                samples.add(sample(18.0f, 0.1f, 68.5f, 10.0f, 0.3, 0.3, false, false, 17.5, true, TARGET_ID));
            } else if (i == 16) {
                samples.add(sample(0.5f, 0.1f, 69.0f, 10.0f, 0.5, 0.4, false, false, 17.0, false, TARGET_ID));
            } else {
                samples.add(sample(0.5f, 0.1f, 30.0f, 10.0f, 20.0, 20.0, false, false, 0.0, false, TARGET_ID));
            }
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        assertTrue(features.impulseSnap() >= 0.75, "Robotic snap should be detected (got " + features.impulseSnap() + ")");
    }

    @Test
    @DisplayName("Multi-target switch snap is detected")
    void testGenuineSwitchSnap_Detected() {
        List<Sample> samples = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            if (i == 8) {
                // Attacking target 1
                samples.add(sample(2.0f, 0.2f, 40.0f, 10.0f, 0.8, 0.5, false, false, 0.1, true, TARGET_ID));
            } else if (i == 9) {
                // Instant snap to target 2 (25 degrees yaw jump) and attack in consecutive tick
                samples.add(sample(25.0f, 0.2f, 65.0f, 10.0f, 0.9, 0.6, false, false, 23.0, true, OTHER_TARGET_ID));
            } else {
                samples.add(sample(1.0f, 0.2f, 40.0f, 10.0f, 10.0, 10.0, false, false, 0.1, false, TARGET_ID));
            }
        }

        Sample[] sampleArr = samples.toArray(new Sample[0]);
        AIFeatures features = AimAI.computeFeatures(sampleArr, 0.0);

        assertTrue(features.switchSnap() >= 0.80, "Switch snap should be detected (got " + features.switchSnap() + ")");
    }

    @Test
    @DisplayName("Multi-indicator requirement: isolated single indicator cannot achieve high cheat score")
    void testMultiIndicatorGating() {
        // When only 1 indicator is moderate, score must be capped below 0.35
        double[] singleIndicator = new double[AimAIModel.INPUT_SIZE];
        singleIndicator[0] = 0.40; // impulseSnap moderate
        double raw = AimAIModel.predict(singleIndicator);
        assertTrue(raw < 0.40, "Single moderate indicator should have low neural score");

        // When multiple indicators combine (e.g. Snap + Lock + GCD anomaly)
        double[] multiIndicators = new double[AimAIModel.INPUT_SIZE];
        multiIndicators[0] = 0.85; // impulseSnap
        multiIndicators[2] = 0.85; // continuousLock
        multiIndicators[7] = 0.50; // gcdAnomaly
        double multiScore = AimAIModel.predict(multiIndicators);
        assertTrue(multiScore > 0.95, "Multiple corroborating cheat indicators must produce high certainty score (got " + multiScore + ")");
    }
}
