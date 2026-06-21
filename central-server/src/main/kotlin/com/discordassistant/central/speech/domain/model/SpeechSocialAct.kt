package com.discordassistant.central.speech.domain.model

/**
 * speech 도메인이 보는 social act(사회적 발화 행위) 코드(NEXA-P14-T004, 순수 도메인).
 *
 * participation 이 SPEAK 와 함께 고른 "어떤 종류의 말" 인지의 안정 코드를 speech 가 **자기 도메인 어휘로** 다시
 * 표현한 값이다 — speech.domain 은 participation 타입을 import 하지 않는다(도메인 순수성·module-dag 경계).
 * 경계 매핑(participation.SocialAct → 이 enum)은 speech 의 application/adapter 가 [fromWireName] 으로 수행한다.
 *
 * 미지 코드는 자유 텍스트로 보존하지 않고 [UNKNOWN] 으로 정규화한다(fail-soft) — 프롬프트 컴파일러가 보수적으로
 * 다룬다(NEXA-P14-T009).
 */
enum class SpeechSocialAct(
    val wireName: String,
) {
    /** 인지/맞장구(짧은 동의·리액션성 응답). */
    ACKNOWLEDGE("acknowledge"),

    /** 동의. */
    AGREE("agree"),

    /** 이견 제시. */
    DISAGREE("disagree"),

    /** 가벼운 장난(banter). */
    TEASE("tease"),

    /** 질문. */
    ASK("ask"),

    /** 사실 정정. */
    CORRECT("correct"),

    /** 자기 개시(자기 상태·관점을 드러냄). */
    SELF_DISCLOSE("self_disclose"),

    /** 주제 전환. */
    CHANGE_TOPIC("change_topic"),

    /** 미지/미분류 — 외부 코드가 모르는 값이면 이리로 정규화한다(보수적 처리). */
    UNKNOWN("unknown"),
    ;

    /** 이 act 가 미지 분류인가 — 컴파일러가 보수적으로 다뤄야 하는지의 가드. */
    val isUnknown: Boolean
        get() = this == UNKNOWN

    /**
     * 이 발화가 **사실 조회(factual lookup)** 를 요구하는가(NEXA-P15-T012). knowledge RAG 는 이 값이 true 일 때만
     * 조건부로 실행된다 — 질문([ASK])·사실 정정([CORRECT])만 외부 사실 근거가 필요하고, 맞장구·동의·장난·자기 개시·
     * 주제 전환 같은 잡담/반응성 발화는 BM25/web search 를 돌리지 않는다(quota-boundary.md·비용 절감).
     * [UNKNOWN] 은 보수적으로 false(불필요한 검색 금지).
     */
    val requiresFactualLookup: Boolean
        get() = this == ASK || this == CORRECT

    companion object {
        private val BY_WIRE_NAME: Map<String, SpeechSocialAct> = entries.associateBy { it.wireName }

        /** 안정 wireName 으로부터 복원한다. 미지 라벨은 [UNKNOWN] 으로 정규화한다(fail-soft). */
        fun fromWireName(wireName: String): SpeechSocialAct = BY_WIRE_NAME[wireName] ?: UNKNOWN
    }
}
