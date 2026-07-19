package com.discordassistant.central.participation.domain.model.action

/**
 * social act(사회적 발화 행위) 분류(NEXA-P08-T002, 순수 도메인·버전 관리). NEXA 가 SPEAK 로 갈 때 "어떤 종류의
 * 말" 인지의 **안정 코드** 집합이다 — ACKNOWLEDGE/AGREE/DISAGREE/TEASE/ASK/ANSWER/CORRECT/SELF_DISCLOSE/CHANGE_TOPIC 등.
 *
 * participation 은 이 코드를 정할 뿐 **문장을 만들지 않는다**(불변식 2) — 실제 문구는 speech 가 만든다.
 *
 * **acceptance(T002) — 자유 텍스트 라벨이 아닌 안정적 코드와 unknown 처리 규칙**:
 * - 각 act 는 enum 상수의 안정 [wireName] 으로 직렬화된다(자유 텍스트 금지 — enum 이름 변경에도 와이어 호환).
 * - 미지 라벨은 절대 자유 텍스트로 보존하지 않고 [UNKNOWN] 으로 정규화한다([fromWireName] 의 unknown 규칙).
 *   ML/외부 정책이 모르는 코드를 보내면 fail-soft 로 [UNKNOWN] 이 되어 정책이 보수적으로 처리한다.
 * - 분류 자체에 버전([CATALOG_VERSION])을 부여해 코드 집합 진화를 추적한다.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
enum class SocialAct(
    val wireName: String,
) {
    /** 인지/맞장구(짧은 동의·리액션성 응답). */
    ACKNOWLEDGE("acknowledge"),

    /** 동의. */
    AGREE("agree"),

    /** 이견 제시. */
    DISAGREE("disagree"),

    /** 가벼운 장난(banter) — 관계·맥락 허용 시에만 정책이 선택한다. */
    TEASE("tease"),

    /** 질문. */
    ASK("ask"),

    /** 상대가 요청한 내용을 장면에 맞는 깊이로 답함. */
    ANSWER("answer"),

    /** 사실 정정. */
    CORRECT("correct"),

    /** 자기 개시(NEXA 가 자기 상태·관점을 드러냄). */
    SELF_DISCLOSE("self_disclose"),

    /** 주제 전환. */
    CHANGE_TOPIC("change_topic"),

    /**
     * 미지/미분류. 외부(ML/정책)에서 알 수 없는 코드가 오면 자유 텍스트로 보존하지 않고 이리로 정규화한다.
     * 정책은 UNKNOWN 을 보수적으로(예: 약하게 가중) 다룬다 — acceptance T002 의 unknown 처리 규칙.
     */
    UNKNOWN("unknown"),
    ;

    /** 이 act 가 미지 분류인가 — 정책이 보수적으로 다뤄야 하는지의 가드. */
    val isUnknown: Boolean
        get() = this == UNKNOWN

    companion object {
        /** social act 코드 집합의 버전(코드 진화 추적 — 데이터셋/계약과 동기화). */
        const val CATALOG_VERSION: Int = 2

        private val BY_WIRE_NAME: Map<String, SocialAct> = entries.associateBy { it.wireName }

        /**
         * 안정 [wireName] 으로부터 [SocialAct] 를 복원한다. **미지 라벨은 자유 텍스트로 보존하지 않고 [UNKNOWN]
         * 으로 정규화** 한다(acceptance T002 의 unknown 처리 규칙 — fail-soft).
         */
        fun fromWireName(wireName: String): SocialAct = BY_WIRE_NAME[wireName] ?: UNKNOWN
    }
}
