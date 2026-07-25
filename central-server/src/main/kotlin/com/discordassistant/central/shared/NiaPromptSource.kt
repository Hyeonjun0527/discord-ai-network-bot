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

    /**
     * 렌더링 결과의 처음부터 모든 값이 동일하다고 보장되는 문자 수를 계산한다.
     * [stableValueChars]에 없는 값은 처음부터 동적이며, 있는 값은 지정한 길이까지만 고정이다.
     */
    fun stablePrefixChars(
        template: String,
        values: Map<String, String>,
        stableValueChars: Map<String, Int>,
    ): Int {
        val unknownStableValues = stableValueChars.keys - values.keys
        require(unknownStableValues.isEmpty()) {
            "cache 고정 길이가 알 수 없는 변수에 지정되었다: ${unknownStableValues.sorted().joinToString()}"
        }
        stableValueChars.forEach { (key, stableChars) ->
            val value = values.getValue(key)
            require(stableChars in 0..value.length) {
                "$key cache 고정 길이가 값 범위를 벗어난다: $stableChars/${value.length}"
            }
        }

        val raw = StringBuilder(template.length + values.values.sumOf(String::length))
        var cursor = 0
        var dynamicBoundary: Int? = null
        marker.findAll(template).forEach { match ->
            raw.append(template, cursor, match.range.first)
            val key = match.groupValues[1]
            val value = values[key].orEmpty()
            val stableChars = stableValueChars[key]
            if (dynamicBoundary == null && (stableChars == null || stableChars < value.length)) {
                dynamicBoundary = raw.length + (stableChars ?: 0)
            }
            raw.append(value)
            cursor = match.range.last + 1
        }
        raw.append(template, cursor, template.length)

        val untrimmed = raw.toString()
        val rendered = untrimmed.trim()
        val trimStart = untrimmed.length - untrimmed.trimStart().length
        val trimEnd = trimStart + rendered.length
        val boundary = dynamicBoundary ?: return rendered.length
        return when {
            boundary <= trimStart -> 0
            boundary >= trimEnd -> rendered.length
            else -> boundary - trimStart
        }
    }
}
