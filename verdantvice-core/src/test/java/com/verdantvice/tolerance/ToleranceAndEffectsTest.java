package com.verdantvice.tolerance;

import com.verdantvice.effects.*;
import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.*;

class ToleranceAndEffectsTest {
    private final ToleranceService tolerance = new ToleranceService();

    @Test
    void toleranceGrowsOnUseAndDecaysDuringBreak() {
        TolerancePolicy policy = TolerancePolicy.defaults();
        ToleranceState initial = ToleranceState.initial(TestFixtures.OWNER, TestFixtures.NOW);
        ToleranceState used = tolerance.recordUse(initial, 2.0, TestFixtures.NOW, policy);
        assertEquals(4.0, used.level(), 0.0001);
        ToleranceState afterTwoDays = tolerance.decay(used,
                TestFixtures.NOW.plus(Duration.ofDays(2)), policy);
        assertEquals(0.0, afterTwoDays.level(), 0.0001);
    }

    @Test
    void disabledToleranceAlwaysHasNeutralMultipliers() {
        TolerancePolicy disabled = new TolerancePolicy(false, 4, 2, 8, .65, .5, .45);
        ToleranceState state = new ToleranceState(TestFixtures.OWNER, 90.0,
                java.util.Optional.empty(), TestFixtures.NOW);
        assertEquals(1.0, tolerance.potencyMultiplier(state, disabled));
        assertEquals(0.0, tolerance.decay(state, TestFixtures.NOW.plusSeconds(5), disabled).level());
    }

    @Test
    void highToleranceReducesPotencyDurationAndNegativeRisk() {
        TolerancePolicy policy = TolerancePolicy.defaults();
        ToleranceState low = new ToleranceState(TestFixtures.OWNER, 0.0,
                java.util.Optional.empty(), TestFixtures.NOW);
        ToleranceState high = new ToleranceState(TestFixtures.OWNER, 100.0,
                java.util.Optional.empty(), TestFixtures.NOW);
        assertTrue(tolerance.potencyMultiplier(high, policy) < tolerance.potencyMultiplier(low, policy));
        assertTrue(tolerance.durationMultiplier(high, policy) < tolerance.durationMultiplier(low, policy));
        assertTrue(tolerance.negativeRiskMultiplier(high, policy)
                < tolerance.negativeRiskMultiplier(low, policy));
    }

    @Test
    void effectCalculationIsBoundedAndUsesProfiles() {
        EffectService effects = new EffectService(tolerance);
        ToleranceState state = ToleranceState.initial(TestFixtures.OWNER, TestFixtures.NOW);
        EffectOutcome outcome = effects.calculate(
                new EffectProfile(Map.of(EffectType.SPEED, 1.0)),
                EffectProfile.empty(),
                100.0, 1.0, 1.0, state, TolerancePolicy.defaults(),
                new PlayerCondition(100, 100, 0, false), EffectPolicy.defaults(),
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(8L));
        assertTrue(outcome.accepted());
        assertTrue(outcome.effects().stream().anyMatch(effect -> effect.type() == EffectType.SPEED));
        assertTrue(outcome.effects().stream()
                .allMatch(effect -> effect.duration().compareTo(EffectPolicy.defaults().maximumDuration()) <= 0));
    }

    @Test
    void rejectsAnotherStrongUseWhileStrongEffectIsActive() {
        EffectOutcome outcome = new EffectService(tolerance).calculate(
                new EffectProfile(Map.of(EffectType.SPEED, 1.0)), EffectProfile.empty(),
                100, 2.0, 1.0, ToleranceState.initial(TestFixtures.OWNER, TestFixtures.NOW),
                TolerancePolicy.defaults(), new PlayerCondition(100, 100, 0, true),
                EffectPolicy.defaults(),
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(9L));
        assertFalse(outcome.accepted());
        assertEquals("active-strong-effect", outcome.rejectionCode());
        assertTrue(outcome.effects().isEmpty());
    }
}
