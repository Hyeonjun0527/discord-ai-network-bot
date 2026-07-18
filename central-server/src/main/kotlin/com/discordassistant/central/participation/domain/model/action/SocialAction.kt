package com.discordassistant.central.participation.domain.model.action

import com.discordassistant.central.participation.domain.model.decision.ActionDelay

/**
 * NEXA 가 지금 장면에서 고를 수 있는 **단 하나의 행동**(NEXA-P08-T001, 순수 도메인 sealed hierarchy·불변).
 *
 * participation-context.md 책임: "지금 이 장면에서 NEXA가 무엇을 할지 단 하나의 행동을 고른다":
 * [Ignore] / [Wait] / [React] / [Speak] / [CancelPending]. **말 자체를 만들거나 보내지 않는다.**
 *
 * **acceptance(T001) — 각 행동이 필요한 payload만 가진다**:
 * - [Ignore]: payload 없음(아무것도 하지 않음).
 * - [Wait]: 더 지켜볼 [delay] 만(다음 재평가 시점).
 * - [React]: reaction 코드([reactionCodes]) — 안정 코드만, 원문/이모지 문자열 자유텍스트 아님.
 * - [Speak]: **문장 필드가 존재하지 않는다** — speech 에 발화 계획을 요청할 [speechRequest] 참조만 가진다.
 *   participation 은 "말하기로 결정" 할 뿐 "말의 내용" 은 speech 가 만든다(불변식 2).
 * - [CancelPending]: 취소할 예약 행동 식별자([pendingActionId]) 만.
 *
 * 즉 **SPEAK 없이 문장 필드가 어떤 분기에도 존재하지 않는다**(타입으로 강제). [Speak.speechRequest] 는
 * 텍스트가 아니라 "speech 에게 무엇을·어떤 형태로 계획하라" 는 참조(원문 비포함)다.
 *
 * 불변식 1(한 번의 평가는 정확히 하나의 행동): sealed 라 한 인스턴스는 정확히 한 분기다.
 *
 * 순수성: Spring/JPA/JDA/adapter·CloudLlm 타입을 일절 참조하지 않는다(participation.domain 규칙,
 * NexaArchitectureTest.nexaDomainsArePure).
 */
sealed interface SocialAction {
    /** 이 행동의 안정 분류 코드(로그·계약 직렬화용 — enum 이름 변경에도 와이어 호환 유지). */
    val kind: SocialActionKind

    /** LLM(generation) 호출을 유발하는가 — quota-boundary.md: SPEAK 만 true(IGNORE/WAIT/REACT/CANCEL 무료). */
    val consumesGenerationQuota: Boolean

    /** 무시: 이 장면에 아무 행동도 하지 않는다(payload 없음). LLM 미호출(quota 무소모). */
    data object Ignore : SocialAction {
        override val kind: SocialActionKind get() = SocialActionKind.IGNORE
        override val consumesGenerationQuota: Boolean get() = false
    }

    /**
     * 대기: 지금은 행동하지 않고 [delay] 후 다시 평가한다(더 지켜봄). LLM 미호출(quota 무소모).
     * IGNORE 와 달리 "다시 볼 시점" 을 남긴다.
     */
    data class Wait(
        val delay: ActionDelay,
        val wakeUpHint: String? = null,
    ) : SocialAction {
        init {
            require(wakeUpHint == null || wakeUpHint.isNotBlank()) { "wakeUpHint 는 빈 문자열일 수 없다" }
        }

        override val kind: SocialActionKind get() = SocialActionKind.WAIT
        override val consumesGenerationQuota: Boolean get() = false
    }

    /**
     * 반응: 발화 없이 reaction(이모지 등)만 단다. [reactionCodes] 는 **안정 코드**(자유 텍스트 아님)로,
     * 실제 이모지 매핑·전송은 actionruntime 의 책임이다. LLM 미호출(quota 무소모).
     */
    data class React(
        val reactionCodes: List<ReactionCode>,
        val delay: ActionDelay = ActionDelay.IMMEDIATE,
    ) : SocialAction {
        init {
            require(reactionCodes.isNotEmpty()) { "React 는 최소 하나의 reactionCode 를 가져야 한다" }
        }

        override val kind: SocialActionKind get() = SocialActionKind.REACT
        override val consumesGenerationQuota: Boolean get() = false
    }

    /**
     * 발화 결정: speech 에 발화 계획을 요청한다([speechRequest]). **문장 필드 없음** — 내용은 speech 가 만든다.
     * LLM(generation) 을 호출하므로 quota 를 소모한다(quota-boundary.md).
     */
    data class Speak(
        val speechRequest: SpeechRequestRef,
        val delay: ActionDelay = ActionDelay.IMMEDIATE,
    ) : SocialAction {
        override val kind: SocialActionKind get() = SocialActionKind.SPEAK
        override val consumesGenerationQuota: Boolean get() = true
    }

    /**
     * 예약 취소: 이전에 예약한 행동([pendingActionId])을 취소한다(장면이 바뀌어 더는 유효하지 않음).
     * generation 을 호출하지 않으므로 quota 무소모(이미 generation 후 취소면 차감은 유지되지만 그 회계는
     * quota 컨텍스트 책임이다).
     */
    data class CancelPending(
        val pendingActionId: PendingActionId,
    ) : SocialAction {
        override val kind: SocialActionKind get() = SocialActionKind.CANCEL_PENDING
        override val consumesGenerationQuota: Boolean get() = false
    }
}

/**
 * SocialAction 분류 코드(순수 도메인 enum). decision log·정책 계약 직렬화에 쓰는 **안정 코드**다 —
 * sealed 분기 클래스 이름이 바뀌어도 [wireName] 만 고정하면 와이어 호환이 유지된다.
 */
enum class SocialActionKind(
    val wireName: String,
) {
    IGNORE("ignore"),
    WAIT("wait"),
    REACT("react"),
    SPEAK("speak"),
    CANCEL_PENDING("cancel_pending"),
}

/**
 * reaction 안정 코드(순수 도메인 value object). 자유 텍스트 이모지가 아니라 의미 코드다 — 실제 이모지/유니코드
 * 매핑과 전송은 actionruntime 이 한다(participation 은 "어떤 반응을 할지" 코드만 정한다).
 */
@JvmInline
value class ReactionCode(
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "reaction code 는 비어 있을 수 없다" }
    }
}

/**
 * speech 에게 보낼 발화 계획 요청 참조(순수 도메인 value object). **원문/문장 텍스트를 담지 않는다** —
 * speech 가 어떤 장면·correlation 에 대해 계획을 세울지 가리키는 식별 참조일 뿐이다(불변식 2: participation 은
 * 텍스트를 만들지 않는다).
 */
data class SpeechRequestRef(
    /** 이 발화 결정의 상관 식별자(장면·결정 로그와 연결, 원문 비포함). */
    val correlationId: String,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId 는 비어 있을 수 없다" }
    }
}

/**
 * actionruntime 에 예약된 행동의 식별자(순수 도메인 value object). CancelPending 이 무엇을 취소하는지 가리킨다.
 * 실제 예약/스케줄링은 actionruntime 책임이며 participation 은 식별자만 운반한다.
 */
@JvmInline
value class PendingActionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" }
    }
}
