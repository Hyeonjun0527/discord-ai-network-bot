package com.discordassistant.central.speech.domain.service.critic

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 발화 후보 **비평가**(NEXA-P14-T017~T020, 순수 도메인 서비스).
 *
 * 이미 생성된 후보 텍스트를 평가해 부적합 후보를 **탈락**시킨다 — 후보를 새로 만들거나 사용자 문장을 고치지 않는다.
 * human-likeness gate 약점(AI 도우미 말투·반복·기억 모순·대상/장면 불일치)을 생성 후에 거르는 안전망이다.
 *
 * **acceptance(공통) — 평가만 한다**: 비평가는 [evaluate] 로 [CriticVerdict] 만 돌려준다. 후보 문자열을 변형하거나
 * 새 사실을 발명하는 메서드가 없다(평가-전용 계약). 탈락은 [CriticVerdict.rejected] 로 표현하고, 선택기(T021)가
 * 모든 비평가를 통과한 후보 중에서만 고른다.
 *
 * 순수성: Spring/JPA/JDA 미참조. speech 도메인 타입·표준 타입만.
 */
fun interface SpeechCritic {
    /**
     * [candidate] 가 [packet] 장면에서 적합한지 평가한다. 부적합하면 [CriticVerdict.rejected], 적합하면
     * [CriticVerdict.accepted]. 절대 후보 텍스트를 바꾸지 않는다(평가-전용).
     */
    fun evaluate(
        candidate: CandidateText,
        packet: SpeechScenePacket,
    ): CriticVerdict
}

/**
 * 비평 대상 후보 텍스트(NEXA-P14-T017, 순수 값 객체). 비평가는 도메인이라 application 의 SpeechCandidate 를 모른다 —
 * 선택기(T021)가 후보 버블을 이 도메인 어휘로 어댑트해 비평가에 넘긴다(도메인 순수성·경계 분리).
 */
data class CandidateText(
    /** 후보 식별자(탈락 추적·로그용). */
    val candidateId: String,
    /** 후보 메시지 버블들(각 조각 텍스트). 빈 조각은 비평 전에 제거돼 있다고 가정하지 않는다 — 비평가가 방어한다. */
    val bubbles: List<String>,
) {
    init {
        require(candidateId.isNotBlank()) { "candidateId 는 비어 있을 수 없다" }
    }

    /** 모든 버블을 한 줄로 이은 평문(규칙 매칭용). */
    val joined: String
        get() = bubbles.joinToString(separator = " ") { it.trim() }.trim()
}

/**
 * 비평 결과(NEXA-P14-T017~T020, 순수 값 객체·불변). 통과/탈락 + 탈락 사유 코드.
 * 사유는 자유 텍스트가 아니라 [CriticReason] enum 으로 — shadow 평가(T024)가 사유별 rate 를 집계한다.
 */
data class CriticVerdict(
    val accepted: Boolean,
    /** 탈락 사유(통과면 null). */
    val reason: CriticReason? = null,
) {
    /** 탈락 여부(가독성). */
    val rejected: Boolean
        get() = !accepted

    init {
        require(accepted == (reason == null)) {
            "accepted 와 reason 은 정확히 한쪽만 — accepted=$accepted reason=$reason"
        }
    }

    companion object {
        /** 통과(이 비평가는 이 후보를 거르지 않는다). */
        val ACCEPTED = CriticVerdict(accepted = true)

        /** [reason] 으로 탈락. */
        fun reject(reason: CriticReason) = CriticVerdict(accepted = false, reason = reason)
    }
}

/**
 * 비평 탈락 사유 코드(NEXA-P14-T017~T020). shadow 평가(T024)가 사유별 비율을 집계한다(assistant-style rate 등).
 */
enum class CriticReason {
    /** AI 도우미 말투(T017 — "도와드릴까요/언제든 말씀하세요" 류). */
    ASSISTANT_STYLE,

    /** 최근 버스트와의 반복·자기복제(T018). */
    REPETITION,

    /** 유효 기억과의 모순(T019). */
    MEMORY_CONTRADICTION,

    /** 대상·장면 불일치(T020 — cross-thread 끌어오기 등). */
    TARGET_OR_SCENE_MISMATCH,

    /** 시스템 지침·API 키·내부 schema·hidden ID 등 비밀 노출(P17-T003). */
    SECRET_DISCLOSURE,

    /** 인간 사칭 — AI 임을 부정하거나 사람인 척함(P17-T017). */
    HUMAN_IMPERSONATION,

    /** participation 이 확정한 burst shape(조각 수·길이·reaction-only)와 후보가 맞지 않음. */
    BURST_SHAPE_MISMATCH,

    /** 장문 위로·설명식 답변·과한 친밀감·감정 단정처럼 사람 채팅 경계를 벗어난 후보. */
    CONVERSATIONAL_BOUNDARY,
}
