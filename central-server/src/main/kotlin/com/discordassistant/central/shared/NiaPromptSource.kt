package com.discordassistant.central.shared

/** 니아의 판단·발화 모델에 들어가는 운영자 편집 가능 원문. */
enum class NiaPromptKey(
    val wireName: String,
    val group: String,
    val title: String,
    val description: String,
    val requiredPlaceholders: Set<String> = emptySet(),
) {
    SAFETY_GUARDRAIL(
        "safety_guardrail",
        "안전",
        "니아 안전 원칙",
        "니아의 /ask 입력에서 다른 지시보다 먼저 들어가는 안전 원칙",
    ),
    IDENTITY_PERSONA(
        "identity_persona",
        "정체성",
        "기본 정체성",
        "니아가 누구이며 어떤 태도로 대화하는지 정하는 기본 원문",
    ),
    VOICE_PRINCIPLES(
        "voice_principles",
        "정체성",
        "대화 말투 원칙",
        "니아의 /ask 및 채널 대화에 공통으로 들어가는 말투 원칙",
    ),
    SPEECH_PERSONA_RULES(
        "speech_persona_rules",
        "정체성",
        "자발 대화 추가 원칙",
        "니아가 Discord 대화에 스스로 참여할 때 정체성 뒤에 추가되는 원칙",
    ),
    IDENTITY_PROHIBITIONS(
        "identity_prohibitions",
        "정체성",
        "발화 금지 항목",
        "발화 모델의 하지 않을 것 목록. 한 줄에 한 항목을 입력",
    ),
    JUDGE_TEMPLATE(
        "judge_template",
        "판단",
        "상황 판단 모델 입력",
        "현재 장면 JSON과 전역 few-shot, RAG를 받아 행동을 고르는 전체 템플릿",
        setOf("outputSchema", "inputJson"),
    ),
    JUDGE_REPAIR_TEMPLATE(
        "judge_repair_template",
        "판단",
        "판단 출력 복구 입력",
        "판단 모델이 잘못된 형식으로 답했을 때 두 번째 호출에 붙는 원문",
        setOf("rejectionCode", "outputSchema"),
    ),
    FEW_SHOT_TEMPLATE(
        "few_shot_template",
        "예시 조립",
        "대화 예시 조립 형식",
        "전역 few-shot과 검색된 RAG 대화를 발화 모델에 넣는 형식",
        setOf("heading", "examples"),
    ),
    SCENE_ISOLATION_TEMPLATE(
        "scene_isolation_template",
        "발화 생성",
        "대화 원문 격리 형식",
        "Discord 원문을 지시가 아닌 관찰 데이터로 감싸는 형식",
        setOf("turns"),
    ),
    SPEECH_SYSTEM_TEMPLATE(
        "speech_system_template",
        "발화 생성",
        "발화 후보 시스템 입력",
        "정체성·상황 판단·행위·메시지 수·few-shot을 합쳐 실제 문장 후보를 만드는 전체 시스템 템플릿",
        setOf("identity", "participationDecision", "socialActInstruction", "burstInstruction", "outputContract"),
    ),
    SPEECH_USER_TEMPLATE(
        "speech_user_template",
        "발화 생성",
        "발화 후보 사용자 입력",
        "현재 대화·응답 대상·외부 사실 검증 결과를 합치는 전체 사용자 템플릿",
        setOf("payload", "quotedScene", "rawContext", "responseTarget", "grounding"),
    ),
    SOCIAL_ACT_INSTRUCTIONS(
        "social_act_instructions",
        "발화 생성",
        "사회적 행위별 지침",
        "ACKNOWLEDGE, AGREE 등 행위별 지침. KEY=원문 형식으로 한 줄에 하나씩 입력",
    ),
    BURST_INSTRUCTIONS(
        "burst_instructions",
        "발화 생성",
        "메시지 분할 지침",
        "REACTION_ONLY, SINGLE, MULTI, TAIL 지침. KEY=원문 형식이며 count와 maxChars 변수를 사용할 수 있음",
    ),
    SPEECH_OUTPUT_TEMPLATE(
        "speech_output_template",
        "발화 생성",
        "발화 후보 출력 형식",
        "발화 모델이 후보를 JSON으로 반환하게 하는 형식",
        setOf("candidateCount"),
    ),
    SPEECH_COMBINE_TEMPLATE(
        "speech_combine_template",
        "발화 생성",
        "클라우드 발화 최종 입력",
        "시스템 입력·감정 지침·사용자 입력을 실제 모델 호출 직전에 합치는 형식",
        setOf("systemPrompt", "toneDirective", "userPrompt"),
    ),
    ACTION_EVALUATOR_TEMPLATE(
        "action_evaluator_template",
        "최종 행동 선택",
        "완전 행동 후보 평가 입력",
        "실제 문구·침묵·리액션 후보 중 최종 행동 하나를 고르는 전체 템플릿",
        setOf(
            "speechIntent",
            "socialAct",
            "provisionalDecision",
            "provisionalConfidence",
            "contextVersion",
            "seed",
            "triggerMessageRef",
            "stateRefs",
            "enforcement",
            "recentScene",
            "rawContext",
            "candidates",
        ),
    ),
    ASK_NIA_TEMPLATE(
        "ask_nia_template",
        "/ask",
        "니아 /ask 전체 입력",
        "니아가 /ask 또는 채널 AI로 답할 때 쓰는 전체 기본 형식",
        setOf("safety", "persona", "voicePrinciples", "managedFewShot", "relation", "userMessage"),
    ),
    ;

    companion object {
        private val byWireName = entries.associateBy(NiaPromptKey::wireName)

        fun fromWireName(value: String): NiaPromptKey? = byWireName[value]
    }
}

data class NiaPromptDocument(
    val key: NiaPromptKey,
    val content: String,
)

fun interface NiaPromptSource {
    fun documents(): Map<NiaPromptKey, String>

    fun text(key: NiaPromptKey): String = documents()[key] ?: NiaPromptDefaults.text(key)
}

object CodeNiaPromptSource : NiaPromptSource {
    override fun documents(): Map<NiaPromptKey, String> = NiaPromptDefaults.documents
}

object NiaPromptTemplate {
    private val marker = Regex("\\{\\{([A-Za-z][A-Za-z0-9]*)}}")

    fun render(
        template: String,
        values: Map<String, String>,
    ): String {
        val rendered = marker.replace(template) { match -> values[match.groupValues[1]].orEmpty() }
        return rendered.trim()
    }

    fun placeholders(template: String): Set<String> = marker.findAll(template).map { it.groupValues[1] }.toSet()
}
