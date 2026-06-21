package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import java.time.Duration
import java.time.Instant

/**
 * 동적 gap feature 계산기(NEXA-P04-T006, 순수 함수). 작성자 최근 간격·채널 tempo·typing 신호를 **feature 값**으로
 * 계산하되, 최종 버스트 경계 **결정은 baseline 규칙([FixedGapBurstSegmenter])이 소유**한다 — 여기서는 신호만 만든다.
 *
 * 순수성: Spring/JPA/JDA 타입 미참조. 표준 [Instant]/[Duration] 만 쓴다. 객체 상태 없음(모든 입력을 인자로 받는다).
 *
 * **acceptance(T006) — replay 결정론·미래 비참조**: 모든 feature 는 **이미 본 과거 조각·현재 조각·과거 typing 신호**만
 * 입력으로 받는다. 미래 이벤트를 인자로 받지 않으므로, 같은 입력을 replay 하면 항상 같은 feature 가 나온다. 부동소수점
 * 평균 대신 정수/Duration 산술만 써서 플랫폼 간 동일 결과를 보장한다.
 */
object BurstGapFeatures {
    /**
     * 직전 OPEN 버스트의 마지막 조각과 [next] 조각 사이 실제 간격. 버스트가 없으면 null(첫 조각이라 간격 미정의).
     * baseline segmenter 가 이 값을 gap 정책과 비교해 경계를 결정한다(이 계산기는 값만 제공).
     */
    fun gapSincePrevious(
        openBurst: UtteranceBurst?,
        next: MessageFragment,
    ): Duration? = openBurst?.let { Duration.between(it.lastFragmentAt, next.occurredAt) }

    /**
     * 작성자의 OPEN 버스트 내 최근 인접 조각들의 **평균 간격**(작성자 타이핑 tempo 추정). 조각이 1개뿐이면 null.
     * 정수 밀리초 평균(나머지 버림)이라 replay 결정론적이다 — 미래 조각은 보지 않는다(현재 버스트 내부만).
     */
    fun authorRecentCadence(openBurst: UtteranceBurst?): Duration? {
        val fragments = openBurst?.fragments ?: return null
        if (fragments.size < 2) return null
        var totalMillis = 0L
        for (i in 1 until fragments.size) {
            totalMillis += Duration.between(fragments[i - 1].occurredAt, fragments[i].occurredAt).toMillis()
        }
        return Duration.ofMillis(totalMillis / (fragments.size - 1))
    }

    /**
     * 작성자가 [at] 시점에 아직 typing 중인 것으로 관찰됐는가(가장 최근 typing 만료 시각 [typingExpiresAt] 기준).
     * typing 신호가 없거나(null) 이미 만료됐으면 false. 미래 신호가 아니라 **이미 관찰된** 만료 시각만 본다(replay 안전).
     */
    fun isTypingActive(
        typingExpiresAt: Instant?,
        at: Instant,
    ): Boolean = typingExpiresAt != null && at.isBefore(typingExpiresAt)
}
