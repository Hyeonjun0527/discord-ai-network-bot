package com.discordassistant.central.socialmemory.domain.service.emotion

import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionEventGrade
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionEventSign
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionProfile
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 사건 기반 감정 엔진 — core `nia_engine/emotion.py`(Stage D D1, 게이트 G4 통과) 이식. **순수 함수·무상태.**
 *
 * core 가 검증한 성질을 Kotlin 으로 그대로 옮긴다(같은 수식·상수 1:1):
 * - **두 상태 분리**: reaction(반감기 1h·범위 ±0.20·δ 0.08)와 mood(반감기 36h·범위 ±0.10·δ 0.006).
 * - **bounded 수학**: `x_t = μ + (x_{t-1}−μ)·2^(−Δt/H) + Δx` (SSOT §11.5). B4 관계 수학과 같은 식, 다른 시정수.
 * - **I1 랜덤 0**: 모든 변화는 등급에서 결정적. [Instant.now]/[System] 미사용 — [now] 를 인자로 받는다
 *   (SocialStateDecay 와 같은 acceptance: 시간 이동 테스트 가능).
 * - **I2 등급만**: GLM 은 등급([EmotionEventGrade])만, 숫자는 코드. 외부 직접 설정 경로 없음(조작 방지).
 * - **I4 이중 제한**: 단일 사건 |Δx| ≤ δ_max + 범위 clamp.
 * - **I5 시간 감쇠**: 사건 없으면 μ(평소 니아)로 매끄럽게 회귀(비계단식). dt<0 거부, dt=0 no-op.
 * - **G4 핵심**: mood δ_max(0.006) < 렌더 임계(0.025) → **한 사건으로 mood 가 임계에 못 든다**.
 *
 * 범위 — 상태만(오프라인): 응답 말투 반영(렌더·히스테리시스)은 D2(후속). 본 엔진은 정체성(§4)·말투를 건드리지
 * 않는다(I11). Spring/JPA/JDA 미참조 — 표준 java.time·kotlin.math 만.
 */
object EmotionEngine {
    /** reaction 프로필: 순간 반응. 반감기 60분, 범위 ±0.20, 한 사건 ±0.08(순간 크게 튐), 부정 비대칭 1.15. */
    val REACTION: EmotionProfile =
        EmotionProfile(
            name = "reaction",
            mu = 0.0,
            halfLife = Duration.ofMinutes(60),
            xMin = -0.20,
            xMax = 0.20,
            deltaMax = 0.08,
            negativityBias = 1.15,
        )

    /** mood 프로필: 느린 기분. 반감기 36시간, 범위 ±0.10, 한 사건 ±0.006(매우 둔함), 부정 비대칭 1.05. */
    val MOOD: EmotionProfile =
        EmotionProfile(
            name = "mood",
            mu = 0.0,
            halfLife = Duration.ofHours(36),
            xMin = -0.10,
            xMax = 0.10,
            deltaMax = 0.006,
            negativityBias = 1.05,
        )

    /**
     * 렌더 발동선(표현 임계값, SSOT §13). mood 절대값 ≥ 이 값이어야 D2 표현에 반영된다.
     * mood δ_max(0.006) < 이 임계(0.025) → 단일 사건으로 mood 가 임계를 넘을 수 없다(G4).
     */
    const val MOOD_RENDER_THRESHOLD: Double = 0.025

    /**
     * reaction 이 mood 에 약하게 누적되는 비율(SSOT §11). 같은 사건이 reaction(즉시)·mood(배경)에 동시
     * 반영될 때 mood 는 *추가로* 이 비율만큼만 더 둔하게 받는다. mood 자체 δ_max 로 이미 이중 클립된다.
     */
    const val REACTION_TO_MOOD_BLEED: Double = 0.25

    /** 기준점(μ) 감정 — 사건 없는 맥락(또는 조회 미존재) 시. reaction·mood 모두 μ(=0)에서 시작. */
    fun baseline(contextScope: String): EmotionState = EmotionState(contextScope = contextScope, reaction = MOOD.mu, mood = MOOD.mu)

    /**
     * 시간 감쇠(I5) — 사건 없으면 평소(μ)로 회귀. `x_t = μ + (x_prev−μ)·2^(−Δt/H)`.
     * Δt=0 이면 변화 없음, Δt→∞ 이면 μ 수렴. 음수 Δt 거부. 난수 없음(I1).
     */
    fun decay(
        profile: EmotionProfile,
        xPrev: Double,
        lastUpdatedAt: Instant,
        now: Instant,
    ): Double {
        val elapsedMillis = Duration.between(lastUpdatedAt, now).toMillis()
        require(elapsedMillis >= 0) { "now 는 lastUpdatedAt 보다 과거일 수 없다" }
        if (elapsedMillis == 0L) return xPrev
        val halfLives = elapsedMillis.toDouble() / profile.halfLife.toMillis().toDouble()
        val factor = 2.0.pow(-halfLives)
        return profile.mu + (xPrev - profile.mu) * factor
    }

    /**
     * 등급(GLM 판정) → 한 감정 상태의 결정적 변화량 Δx — I1·I2·I4.
     * 등급 충격량을 δ_max 로 단발 클립한 뒤 부정 비대칭(§12.3)·도배 감쇠(1/√(1+n))를 곱하고 δ_max 재클립.
     *
     * @param nRecent 최근 같은 화자 유사 사건 수(도배 감쇠용). 0 = 첫 사건.
     */
    fun eventDelta(
        profile: EmotionProfile,
        grade: EmotionEventGrade,
        sign: EmotionEventSign,
        nRecent: Int = 0,
    ): Double {
        require(nRecent >= 0) { "nRecent 는 음수일 수 없다" }
        // 1) 등급 충격량을 δ_max 로 단발 클립(I4). 부정 비대칭 전 기본 클립.
        val raw = sign.value * grade.impact
        var delta = raw.coerceIn(-profile.deltaMax, profile.deltaMax)
        // 2) 부정 비대칭(§12.3): 부정 방향만 아주 조금 강하게(뒤끝 방지 — 반감기는 안 늘림).
        if (sign == EmotionEventSign.NEGATIVE) delta *= profile.negativityBias
        // 3) 도배 감쇠(1/√(1+n)) — 같은 화자 반복 사건 영향 포화(결정적).
        delta *= dogpile(nRecent)
        // 4) δ_max 재클립 — 비대칭 곱이 δ_max 를 넘지 않게 보장(I4 단일 사건 상한 절대 보존).
        return delta.coerceIn(-profile.deltaMax, profile.deltaMax)
    }

    /** 도배 감쇠 1/√(1+n) — n=0→1.0, n↑→비선형 감소(반복 포화). 결정적(I1). */
    private fun dogpile(nRecent: Int): Double = 1.0 / sqrt(1.0 + nRecent)

    /**
     * 한 감정 상태의 감쇠+갱신 한 스텝 — `x_t = μ+(x−μ)·2^(−Δt/H)+Δx`.
     * 1) 감쇠(I5) 2) 사건 영향 더함(이미 δ 클립됨) 3) 범위 clamp(I4). 난수 없음(I1).
     */
    fun step(
        profile: EmotionProfile,
        xPrev: Double,
        lastUpdatedAt: Instant,
        now: Instant,
        delta: Double,
    ): Double {
        val decayed = decay(profile, xPrev, lastUpdatedAt, now)
        return profile.clamp(decayed + delta)
    }

    /**
     * 순수 함수: 한 사회 사건을 reaction/mood 에 적용한 *새* 감정 상태를 반환한다. 공개 진입점 —
     * 외부가 숫자를 직접 쓸 수 없고 **오직 등급→수학**으로만 감정이 변한다(I2).
     *
     * reaction 은 사건 영향을 온전히(δ 0.08까지 순간 튐), mood 는 매우 둔하게(δ 0.006 × 누출 0.25) 받는다
     * → 강한 사건도 mood 를 한 번에 임계(0.025)로 못 올린다(G4). [affectsMood]=false 면 reaction 만.
     *
     * @param now 현재 시각(주입 — 시스템 시각 직접 접근 금지, I1·acceptance).
     */
    fun applyEvent(
        state: EmotionState,
        now: Instant,
        grade: EmotionEventGrade,
        sign: EmotionEventSign,
        nRecent: Int = 0,
        affectsMood: Boolean = true,
    ): EmotionState {
        val last = state.lastUpdatedAt ?: now // 첫 사건이면 감쇠 0(dt=0).
        require(!now.isBefore(last)) { "now 는 lastUpdatedAt 보다 과거일 수 없다" }

        // reaction: 사건 영향 온전히(순간 반응).
        val reactionDelta = eventDelta(REACTION, grade, sign, nRecent)
        val newReaction = step(REACTION, state.reaction, last, now, reactionDelta)

        // mood: 매우 둔하게. 자체 δ_max + reaction→mood 누출 비율로 한 번 더 약화.
        val moodDelta =
            if (affectsMood) eventDelta(MOOD, grade, sign, nRecent) * REACTION_TO_MOOD_BLEED else 0.0
        val newMood = step(MOOD, state.mood, last, now, moodDelta)

        return EmotionState(
            contextScope = state.contextScope,
            reaction = newReaction,
            mood = newMood,
            lastUpdatedAt = now,
        )
    }

    /**
     * 읽기 전용 조회 — [now] 까지의 감쇠(I5)를 *반영해* 반환하되 저장 상태는 바꾸지 않는다(순수 읽기).
     * 맥락 격리(I7): contextScope 키이므로 다른 서버/채널 기분이 유입되지 않는다.
     */
    fun readDecayed(
        state: EmotionState,
        now: Instant,
    ): EmotionState {
        val last = state.lastUpdatedAt ?: return state
        require(!now.isBefore(last)) { "now 는 lastUpdatedAt 보다 과거일 수 없다" }
        return EmotionState(
            contextScope = state.contextScope,
            reaction = decay(REACTION, state.reaction, last, now),
            mood = decay(MOOD, state.mood, last, now),
            lastUpdatedAt = now,
        )
    }
}
