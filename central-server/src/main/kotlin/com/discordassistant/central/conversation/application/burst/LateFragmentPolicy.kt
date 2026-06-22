package com.discordassistant.central.conversation.application.burst

import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import java.time.Duration

/**
 * late fragment 처리 정책(NEXA-P04-T016, application). 허용 창([lateArrivalWindow]) 뒤에 도착한 **과거 메시지**
 * (occurredAt 은 과거지만 receivedAt 이 늦음 — 네트워크 지연·gateway resume·재배달)를 기존 버스트 교정으로 흡수할지,
 * 독립 late 버스트로 처리할지 결정한다.
 *
 * application 레이어 소속 — 순수 도메인 타입만 보고 Spring/JPA/JDA 를 참조하지 않는다. 시각 비교는 fragment 가 운반하는
 * occurredAt/receivedAt 만 쓰며 wall-clock 을 읽지 않는다(결정론).
 *
 * **acceptance(T016) — occurredAt/receivedAt 규칙으로 결정론**: 결정은 오직 (후보 버스트 마지막 조각의 occurredAt,
 * late 조각의 occurredAt, late 조각의 receivedAt)과 정책 창만으로 내려진다. 같은 입력이면 항상 같은 결정이 나온다
 * (wall-clock·도착 순서 비참조).
 */
class LateFragmentPolicy(
    /** 버스트 마지막 조각 이후 이 창 **이내**에 발생(occurredAt)한 late 조각만 그 버스트 교정으로 흡수한다. */
    private val lateArrivalWindow: Duration,
) {
    init {
        require(!lateArrivalWindow.isNegative) { "lateArrivalWindow 는 음수일 수 없다" }
    }

    /**
     * [late] 조각을 [candidate] 버스트(같은 작성자·위치의 가장 최근 finalize 된 버스트, 없으면 null)에 비추어 결정한다.
     *
     * - [candidate] 가 없으면 항상 [LateFragmentDecision.StandaloneLateBurst] (붙일 대상 없음).
     * - late 조각의 occurredAt 이 candidate 마지막 조각 occurredAt **이전이거나 같고**, 두 occurredAt 간격이
     *   [lateArrivalWindow] 이내면 candidate 교정으로 흡수([LateFragmentDecision.CorrectExistingBurst]).
     * - occurredAt 이 candidate 마지막 조각보다 **나중**이거나(과거 메시지가 아님), 창을 벗어나면 독립 late 버스트.
     *
     * receivedAt 은 "왜 late 인가"(occurredAt < receivedAt 도착 지연)를 설명하지만 **결정을 흔들지 않는다** — 결정은
     * occurredAt 기준이라 같은 메시지가 언제 도착하든(receivedAt 이 다르든) 동일하게 분류된다(결정론).
     */
    fun decide(
        candidate: UtteranceBurst?,
        late: MessageFragment,
    ): LateFragmentDecision {
        if (candidate == null) return LateFragmentDecision.StandaloneLateBurst

        val anchor = candidate.lastFragmentAt
        // 과거 메시지가 아니면(late 의 occurredAt 이 anchor 보다 나중) 정상 경로 — late 처리 대상 아님 → 독립.
        if (late.occurredAt.isAfter(anchor)) return LateFragmentDecision.StandaloneLateBurst

        val gap = Duration.between(late.occurredAt, anchor)
        return if (gap <= lateArrivalWindow) {
            LateFragmentDecision.CorrectExistingBurst(candidate)
        } else {
            LateFragmentDecision.StandaloneLateBurst
        }
    }
}

/**
 * late fragment 결정 결과(sealed). 호출자가 이 결정을 교정 흡수(CORRECTED) 또는 새 버스트 생성에 적용한다.
 */
sealed interface LateFragmentDecision {
    /** 기존 [burst] 의 교정으로 흡수한다(창 이내 과거 메시지 — burstId 유지, T014 교정 경로 재사용). */
    data class CorrectExistingBurst(
        val burst: UtteranceBurst,
    ) : LateFragmentDecision

    /** 창을 벗어났거나 붙일 후보가 없어 독립 late 버스트로 처리한다. */
    data object StandaloneLateBurst : LateFragmentDecision
}
