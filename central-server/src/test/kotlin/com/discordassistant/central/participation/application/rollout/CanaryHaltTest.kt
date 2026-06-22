package com.discordassistant.central.participation.application.rollout

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T023 결정 코어 acceptance: 과다 발화/complaint/stale send/privacy error/model mismatch 시
 * LIVE→SHADOW/OFF 강등을 판정한다.
 */
class CanaryHaltTest {
    private val limits = CanaryLimits(maxUtterancesPerHour = 10, maxComplaints = 2, maxStaleSends = 1)

    @Test
    fun `no halt when within limits on live`() {
        val d = CanaryHalt.evaluate(ShadowMode.LIVE, CanarySignals(utterancesPerHour = 5), limits)
        assertFalse(d.shouldHalt)
    }

    @Test
    fun `over-talk demotes live to shadow predict`() {
        val d = CanaryHalt.evaluate(ShadowMode.LIVE, CanarySignals(utterancesPerHour = 11), limits)
        assertTrue(d.shouldHalt)
        assertEquals(ShadowMode.SHADOW_PREDICT, d.target)
        assertTrue(d.reasons.contains(HaltReason.OVER_TALK))
    }

    @Test
    fun `complaints over limit demote to shadow predict`() {
        val d = CanaryHalt.evaluate(ShadowMode.CANARY, CanarySignals(complaints = 3), limits)
        assertTrue(d.shouldHalt)
        assertEquals(ShadowMode.SHADOW_PREDICT, d.target)
        assertTrue(d.reasons.contains(HaltReason.COMPLAINT))
    }

    @Test
    fun `stale send over limit demotes to shadow predict`() {
        val d = CanaryHalt.evaluate(ShadowMode.LIVE, CanarySignals(staleSends = 2), limits)
        assertTrue(d.shouldHalt)
        assertEquals(ShadowMode.SHADOW_PREDICT, d.target)
        assertTrue(d.reasons.contains(HaltReason.STALE_SEND))
    }

    @Test
    fun `privacy error immediately halts to OFF`() {
        val d = CanaryHalt.evaluate(ShadowMode.LIVE, CanarySignals(privacyErrors = 1), limits)
        assertTrue(d.shouldHalt)
        assertEquals(ShadowMode.OFF, d.target) // privacy 위반은 가장 강한 중단(OFF).
        assertTrue(d.reasons.contains(HaltReason.PRIVACY_ERROR))
    }

    @Test
    fun `model mismatch immediately halts to OFF`() {
        val d = CanaryHalt.evaluate(ShadowMode.CANARY, CanarySignals(modelMismatches = 1), limits)
        assertTrue(d.shouldHalt)
        assertEquals(ShadowMode.OFF, d.target)
        assertTrue(d.reasons.contains(HaltReason.MODEL_MISMATCH))
    }

    @Test
    fun `hard breach wins when combined with soft breach`() {
        // privacy + over-talk 동시 → 가장 강한 OFF 우선.
        val d =
            CanaryHalt.evaluate(
                ShadowMode.LIVE,
                CanarySignals(utterancesPerHour = 99, privacyErrors = 1),
                limits,
            )
        assertEquals(ShadowMode.OFF, d.target)
        assertTrue(d.reasons.contains(HaltReason.PRIVACY_ERROR))
        assertTrue(d.reasons.contains(HaltReason.OVER_TALK))
    }

    @Test
    fun `no halt when not in real-send mode - already safe`() {
        // 이미 전송이 꺼진 단계(SHADOW_PREDICT/OFF)는 중단할 것이 없다.
        val d = CanaryHalt.evaluate(ShadowMode.SHADOW_PREDICT, CanarySignals(utterancesPerHour = 999, privacyErrors = 5), limits)
        assertFalse(d.shouldHalt)
    }
}
