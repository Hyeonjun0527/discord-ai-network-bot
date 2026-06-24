package com.discordassistant.central.socialmemory.domain.service.emotion

import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionEventGrade
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionEventSign
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Emotion 도메인 이식 검증 — core `tests/test_emotion.py`(22+ PASS, 게이트 G4)가 보증한 성질을 Kotlin 으로 재현.
 * [Instant.now] 미사용 — [now] 를 옮겨 결정론적으로 검증(시간 이동).
 */
class EmotionEngineTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val scope = "guild:1#chan:2"

    private fun fresh() = EmotionState(contextScope = scope)

    @Test
    fun `reaction 과 mood 는 다른 시정수 — reaction 이 훨씬 빨리 감쇠한다`() {
        // 같은 시작값에서 같은 경과(2h) — reaction(H 1h)이 mood(H 36h)보다 크게 줄어든다.
        val after2h = t0.plus(Duration.ofHours(2))
        val r = EmotionEngine.decay(EmotionEngine.REACTION, 0.10, t0, after2h)
        val m = EmotionEngine.decay(EmotionEngine.MOOD, 0.10, t0, after2h)
        assertTrue(r < m, "reaction($r) 이 mood($m) 보다 더 많이 감쇠해야 한다")
        // reaction: 2반감기 → ~1/4. mood: 2/36 반감기 → 거의 그대로.
        assertEquals(0.10 * 0.25, r, 1e-9)
    }

    @Test
    fun `강한 사건 1회로 mood 가 렌더 임계에 못 든다 — G4 핵심`() {
        val s = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.STRONG, EmotionEventSign.POSITIVE)
        assertTrue(abs(s.mood) < EmotionEngine.MOOD_RENDER_THRESHOLD, "mood ${s.mood} < 0.025 이어야 한다")
        // reaction 은 순간 크게 튄다(둔하지 않음).
        assertTrue(s.reaction > s.mood, "reaction(${s.reaction}) 이 mood(${s.mood}) 보다 커야 한다")
    }

    @Test
    fun `mood 단일 사건 변화는 delta_max 이하`() {
        val s = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.STRONG, EmotionEventSign.POSITIVE)
        assertTrue(abs(s.mood) <= EmotionEngine.MOOD.deltaMax + 1e-12, "mood ${s.mood} ≤ 0.006")
    }

    @Test
    fun `사건 없이 시간 지나면 mu(평소 니아)로 회복 — 비계단식`() {
        var s = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.STRONG, EmotionEventSign.NEGATIVE)
        val afterShock = abs(s.reaction)
        // 1h 뒤(reaction 1반감기) — 절반으로 매끄럽게 감소(점프 아님).
        s = EmotionEngine.readDecayed(s, t0.plus(Duration.ofHours(1)))
        assertTrue(abs(s.reaction) < afterShock, "reaction 이 회복(감소)해야 한다")
        // 충분히 오래(수일) 뒤 → μ(0)로 수렴.
        s = EmotionEngine.readDecayed(s, t0.plus(Duration.ofDays(10)))
        assertTrue(abs(s.reaction) < 1e-3 && abs(s.mood) < 1e-3, "μ(0) 로 수렴해야 한다")
    }

    @Test
    fun `결정적 — 같은 입력은 같은 출력(랜덤 0, I1)`() {
        val a = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.CLEAR, EmotionEventSign.POSITIVE)
        val b = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.CLEAR, EmotionEventSign.POSITIVE)
        assertEquals(a.reaction, b.reaction, 0.0)
        assertEquals(a.mood, b.mood, 0.0)
    }

    @Test
    fun `부정 비대칭 — 부정 사건이 같은 등급 긍정보다 reaction 충격이 조금 크다`() {
        val pos = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.CLEAR, EmotionEventSign.POSITIVE)
        val neg = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.CLEAR, EmotionEventSign.NEGATIVE)
        assertTrue(abs(neg.reaction) > abs(pos.reaction), "부정(${neg.reaction}) > 긍정(${pos.reaction})")
        // 단 1.15배 이하(이중 페널티 금지) — δ_max 재클립 보존.
        assertTrue(abs(neg.reaction) <= abs(pos.reaction) * 1.15 + 1e-9)
    }

    @Test
    fun `도배 감쇠 — 반복 사건일수록 한 번의 영향이 비선형 약화`() {
        val first = EmotionEngine.eventDelta(EmotionEngine.REACTION, EmotionEventGrade.MILD, EmotionEventSign.POSITIVE, nRecent = 0)
        val tenth = EmotionEngine.eventDelta(EmotionEngine.REACTION, EmotionEventGrade.MILD, EmotionEventSign.POSITIVE, nRecent = 9)
        assertEquals(first / kotlin.math.sqrt(10.0), tenth, 1e-12)
        assertTrue(tenth < first)
    }

    @Test
    fun `affectsMood=false 면 사건이 mood 를 안 건드린다`() {
        val s = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.STRONG, EmotionEventSign.POSITIVE, affectsMood = false)
        assertEquals(0.0, s.mood, 0.0)
        assertTrue(s.reaction > 0.0)
    }

    @Test
    fun `범위 clamp — 같은 사건 반복해도 reaction 이 범위를 안 넘는다(I4)`() {
        var s = fresh()
        var now = t0
        repeat(50) {
            s = EmotionEngine.applyEvent(s, now, EmotionEventGrade.STRONG, EmotionEventSign.POSITIVE)
            now = now.plusMillis(1) // 거의 즉시 — 감쇠 최소, 누적 압박.
        }
        assertTrue(s.reaction <= EmotionEngine.REACTION.xMax + 1e-12, "reaction ${s.reaction} ≤ 0.20")
        assertTrue(s.mood <= EmotionEngine.MOOD.xMax + 1e-12, "mood ${s.mood} ≤ 0.10")
    }

    @Test
    fun `now 가 과거면 거부(음수 dt 금지)`() {
        val s = EmotionEngine.applyEvent(fresh(), t0, EmotionEventGrade.MILD, EmotionEventSign.POSITIVE)
        assertThrows(IllegalArgumentException::class.java) {
            EmotionEngine.applyEvent(s, t0.minusSeconds(1), EmotionEventGrade.MILD, EmotionEventSign.POSITIVE)
        }
    }

    @Test
    fun `baseline 은 mu(0)에서 시작 — 미초기화`() {
        val b = EmotionEngine.baseline(scope)
        assertEquals(0.0, b.reaction, 0.0)
        assertEquals(0.0, b.mood, 0.0)
    }
}
