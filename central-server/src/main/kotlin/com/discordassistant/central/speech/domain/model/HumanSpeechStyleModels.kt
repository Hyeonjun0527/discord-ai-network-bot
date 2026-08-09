package com.discordassistant.central.speech.domain.model

/**
 * 비공개 사람 대화에서 추출한 **말투 참고 카드**의 반응 방식이다.
 *
 * Judge의 판단용 분류가 아니라, Speech가 현재 장면에서 어떤 반응의 리듬을 참고할지 고르는 작은 검색 축이다.
 */
enum class HumanSpeechResponseMode(
    val retrievalDescription: String,
) {
    REACTION("뜻밖의 이야기나 반가운 소식에 짧은 놀람·웃음·감탄으로 바로 반응한다"),
    ALIGNMENT("상대의 가벼운 불편이나 감각에 같은 편으로 맞장구치고 내 느낌을 짧게 보탠다"),
    PLAY("친한 사이의 가벼운 장난·놀림·과장을 부담 없이 되받아 티키타카를 잇는다"),
    FOLLOW_UP("상대가 말한 상태·이유·진행 중 필요한 부분을 짧게 더 묻는다"),
    SPECULATION("확실하지 않은 일을 단정하지 않고 가능성을 남겨 가볍게 짐작한다"),
    CARE("힘들거나 아픈 상태에 걱정부터 짧게 보이고 부담 없이 챙긴다"),
    COORDINATION("함께 할 일의 선택·시간·다음 행동을 짧게 확인하거나 제안한다"),
}

/**
 * 같은 반응 방식 안에서 실제 답변 리듬을 구분하는 작은 검색 축이다.
 *
 * 주제·사건·인물 분류가 아니라, Speech가 어느 식의 짧은 반응을 참고할지 정하는 닫힌 enum이다.
 */
enum class HumanSpeechStyleResponseMove(
    val responseMode: HumanSpeechResponseMode,
) {
    REACTION_GOOD_NEWS(HumanSpeechResponseMode.REACTION),
    REACTION_SURPRISE(HumanSpeechResponseMode.REACTION),
    REACTION_FUNNY(HumanSpeechResponseMode.REACTION),
    ALIGNMENT_COMPLAINT(HumanSpeechResponseMode.ALIGNMENT),
    ALIGNMENT_LOW_ENERGY(HumanSpeechResponseMode.ALIGNMENT),
    PLAY_COMPETITIVE_TEASE(HumanSpeechResponseMode.PLAY),
    PLAY_FRIENDLY_TEASE(HumanSpeechResponseMode.PLAY),
    PLAY_LIGHT_EXAGGERATION(HumanSpeechResponseMode.PLAY),
    FOLLOW_UP_STATUS(HumanSpeechResponseMode.FOLLOW_UP),
    FOLLOW_UP_PROGRESS(HumanSpeechResponseMode.FOLLOW_UP),
    FOLLOW_UP_CHANGE(HumanSpeechResponseMode.FOLLOW_UP),
    FOLLOW_UP_CAUSE(HumanSpeechResponseMode.FOLLOW_UP),
    SPECULATION_CAUSE(HumanSpeechResponseMode.SPECULATION),
    SPECULATION_FUTURE(HumanSpeechResponseMode.SPECULATION),
    SPECULATION_PRESENT(HumanSpeechResponseMode.SPECULATION),
    CARE_PHYSICAL(HumanSpeechResponseMode.CARE),
    CARE_FATIGUE(HumanSpeechResponseMode.CARE),
    CARE_EMOTIONAL(HumanSpeechResponseMode.CARE),
    COORDINATION_CHOICE(HumanSpeechResponseMode.COORDINATION),
    COORDINATION_TIME(HumanSpeechResponseMode.COORDINATION),
    COORDINATION_ACTION(HumanSpeechResponseMode.COORDINATION),
    COORDINATION_ROLE(HumanSpeechResponseMode.COORDINATION),
}

/**
 * 카드의 앞 대화에서만 확인한, 현재 장면과 비교할 수 있는 닫힌 의미 단서다.
 *
 * [HumanSpeechStyleResponseMove]가 카드 속 사람이 실제로 한 반응을 말한다면, 이 값은 그 반응이 나온 앞 장면의
 * 일반적인 결을 말한다. 원문 주제·인물·사건을 보관하지 않고도 현재 마지막 발화와 카드 장면이 같은 결일 때만
 * 카드별 말풍선 형식·행동 리듬을 쓸 수 있게 한다.
 */
enum class HumanSpeechSceneTrait(
    val responseMode: HumanSpeechResponseMode,
    val retrievalDescription: String,
) {
    REACTION_GOOD_NEWS(HumanSpeechResponseMode.REACTION, "반가운 결과나 좋은 소식이 나온 장면"),
    REACTION_SURPRISE_OR_FUNNY(HumanSpeechResponseMode.REACTION, "뜻밖이거나 웃긴 이야기가 나온 장면"),
    ALIGNMENT_COMPLAINT_OR_LOW_ENERGY(HumanSpeechResponseMode.ALIGNMENT, "가벼운 불평이나 처진 기분을 나누는 장면"),
    PLAY_BANTER(HumanSpeechResponseMode.PLAY, "친한 사이의 가벼운 장난이나 티키타카 장면"),
    FOLLOW_UP_STATUS_OR_PROGRESS(HumanSpeechResponseMode.FOLLOW_UP, "현재 상태나 진행을 더 확인하는 장면"),
    FOLLOW_UP_CHANGE(HumanSpeechResponseMode.FOLLOW_UP, "달라진 일정이나 변화를 확인하는 장면"),
    FOLLOW_UP_CAUSE(HumanSpeechResponseMode.FOLLOW_UP, "왜 그런지 이유를 더 묻는 장면"),
    SPECULATION_CAUSE(HumanSpeechResponseMode.SPECULATION, "이유를 단정하지 않고 짐작하는 장면"),
    SPECULATION_FUTURE(HumanSpeechResponseMode.SPECULATION, "앞으로의 일을 확정하지 않고 짐작하는 장면"),
    SPECULATION_PRESENT(HumanSpeechResponseMode.SPECULATION, "지금 상태를 확신 없이 짐작하는 장면"),
    CARE_PHYSICAL_CONDITION(HumanSpeechResponseMode.CARE, "몸 상태가 좋지 않아 짧게 챙기는 장면"),
    CARE_FATIGUE_OVERLOAD(HumanSpeechResponseMode.CARE, "피곤하거나 지친 상태를 부담 없이 챙기는 장면"),
    CARE_EMOTIONAL_DISTRESS(HumanSpeechResponseMode.CARE, "속상하거나 힘든 감정을 먼저 받아 주는 장면"),
    COORDINATION_CHOICE(HumanSpeechResponseMode.COORDINATION, "무엇을 할지 선택을 함께 조율하는 장면"),
    COORDINATION_TIME(HumanSpeechResponseMode.COORDINATION, "언제 할지 시간을 함께 조율하는 장면"),
    COORDINATION_ACTION_PROPOSAL(HumanSpeechResponseMode.COORDINATION, "다음 행동을 제안하거나 확인하는 장면"),
    COORDINATION_ROLE_OR_ORDER(HumanSpeechResponseMode.COORDINATION, "누가 무엇을 할지 역할이나 순서를 조율하는 장면"),
}

/**
 * 실제 답변에서만 관찰한, 원문 없이 Speech에 전달할 수 있는 짧은 말투 결이다.
 *
 * 이 값은 장면의 사실·사람·사건을 말하지 않는다. 같은 response mode 안에서 선택된 카드가 기본 enum 안내보다 어떤
 * 호흡을 실제로 보였는지만 제한된 문장으로 남긴다. 현재 장면 단서가 없더라도 style-only 값이므로 provider에 안전하게
 * 전달할 수 있고, 의미 단서인 [HumanSpeechSceneTrait]와 [HumanSpeechStyleResponseMove]는 기존의 엄격한 일치
 * 규칙을 그대로 따른다.
 */
enum class HumanSpeechStyleProviderStyleCue(
    val responseMode: HumanSpeechResponseMode,
    val providerGuidance: String,
) {
    REACTION_IMMEDIATE(HumanSpeechResponseMode.REACTION, "감정을 바로 짧게 드러내고 길게 풀지 않는다"),
    REACTION_LAUGH_ALONG(HumanSpeechResponseMode.REACTION, "가벼운 웃음을 섞어 같은 순간을 함께 즐긴다"),
    REACTION_WARM_ACK(HumanSpeechResponseMode.REACTION, "좋은 쪽은 짧고 따뜻하게 반긴다"),
    ALIGNMENT_LOW_KEY_ACK(HumanSpeechResponseMode.ALIGNMENT, "낮은 온도의 짧은 맞장구만 두고 설명을 늘리지 않는다"),
    ALIGNMENT_SHARED_FEELING(HumanSpeechResponseMode.ALIGNMENT, "해결책 대신 같은 편의 짧은 체감 한마디를 보탠다"),
    PLAY_COUNTERTEASE(HumanSpeechResponseMode.PLAY, "상대 장난을 한 번만 가볍게 되받는다"),
    PLAY_LIGHT_EXAGGERATION(HumanSpeechResponseMode.PLAY, "짧은 과장 하나로 티키타카를 이어 간다"),
    FOLLOW_UP_SOFT_CHECK(HumanSpeechResponseMode.FOLLOW_UP, "캐묻지 않고 질문 하나로 부드럽게 확인한다"),
    FOLLOW_UP_DIRECT_CHECK(HumanSpeechResponseMode.FOLLOW_UP, "핵심 한 가지만 또렷하게 묻고 멈춘다"),
    SPECULATION_LIGHT_HEDGE(HumanSpeechResponseMode.SPECULATION, "확정하지 말고 가벼운 가능성만 남긴다"),
    CARE_GENTLE_VALIDATE(HumanSpeechResponseMode.CARE, "걱정을 먼저 보이고 길게 해결하려 들지 않는다"),
    CARE_SOFT_NUDGE(HumanSpeechResponseMode.CARE, "강요하지 않고 쉬거나 조심할 여지를 작게 남긴다"),
    COORDINATION_CONFIRM(HumanSpeechResponseMode.COORDINATION, "새 선택지를 늘리지 말고 상대안을 짧게 확인하거나 받는다"),
    COORDINATION_PROPOSE(HumanSpeechResponseMode.COORDINATION, "다음 한 가지를 가볍게 제안하고 길게 정리하지 않는다"),
    COORDINATION_ASK_ONE(HumanSpeechResponseMode.COORDINATION, "결정을 대신하지 않고 필요한 한 가지만 되묻는다"),
}

/** 카드 response move가 어떻게 관찰·검증됐는지 남기는 감사용 provenance다. */
enum class HumanSpeechStyleResponseMoveProvenance {
    FRESH_VERIFIED,
    HEURISTIC_OBSERVED,
    FRESH_REJECTED,
    NONE,
    ;

    fun matches(responseMove: HumanSpeechStyleResponseMove?): Boolean =
        when (this) {
            FRESH_VERIFIED,
            HEURISTIC_OBSERVED,
            -> responseMove != null
            FRESH_REJECTED,
            NONE,
            -> responseMove == null
        }
}

/**
 * 실제 답변이 보이는 말풍선 리듬이다.
 *
 * responseMove가 "어떤 장면에 필요한 반응인가"를 좁힌다면, 이 값은 카드 속 사람이 실제로 어떤 형식으로
 * 반응했는지를 나타낸다. 주제·사건·인물 분류가 아니며, 같은 장면 enum 안에서 질문형·맞장구형·제안형을
 * 혼동하지 않게 하는 작은 닫힌 축이다.
 */
enum class HumanSpeechStyleResponseForm(
    private val supportedModes: Set<HumanSpeechResponseMode>,
) {
    EXPRESSIVE(setOf(HumanSpeechResponseMode.REACTION)),
    ALIGN_AND_ADD(setOf(HumanSpeechResponseMode.ALIGNMENT)),
    PLAYFUL_RETURN(setOf(HumanSpeechResponseMode.PLAY)),
    QUESTION(setOf(HumanSpeechResponseMode.FOLLOW_UP, HumanSpeechResponseMode.COORDINATION)),
    HEDGED_GUESS(setOf(HumanSpeechResponseMode.SPECULATION)),
    SUPPORTIVE(setOf(HumanSpeechResponseMode.CARE)),
    PROPOSAL(setOf(HumanSpeechResponseMode.COORDINATION)),
    ;

    fun supports(responseMode: HumanSpeechResponseMode): Boolean = responseMode in supportedModes
}

/**
 * 실제 답변의 문구를 외부 embedding에 보내지 않고도, 재사용 가능한 답변 호흡을 구분하는 닫힌 표지다.
 *
 * 주제·사건·인물 label이 아니다. exporter가 답변에서 이 표지를 로컬로 계산하고, runtime은 사람이 실제로 보인
 * 반응 방식의 설명만 embedding 검색면에 보탠다.
 */
enum class HumanSpeechStyleRhythmCue(
    val retrievalDescription: String,
    private val supportedModes: Set<HumanSpeechResponseMode>,
    private val deliveryOnly: Boolean = false,
) {
    SHORT_REACTION("짧은 놀람이나 감탄을 먼저 보인다", setOf(HumanSpeechResponseMode.REACTION)),
    LAUGHTER("웃음으로 가볍게 받는다", setOf(HumanSpeechResponseMode.REACTION, HumanSpeechResponseMode.PLAY)),
    POSITIVE_ACKNOWLEDGMENT("반가운 일을 짧게 긍정하며 받는다", setOf(HumanSpeechResponseMode.REACTION)),
    AGREE_AND_ADD("맞장구친 뒤 내 느낌이나 입장을 한마디 보탠다", setOf(HumanSpeechResponseMode.ALIGNMENT)),
    SHARED_FEELING("상대의 불편한 감각을 같은 편으로 받아 준다", setOf(HumanSpeechResponseMode.ALIGNMENT)),
    PLAYFUL_RETURN("가벼운 놀림이나 장난을 되받는다", setOf(HumanSpeechResponseMode.PLAY)),
    LIGHT_EXAGGERATION("가벼운 과장으로 티키타카를 잇는다", setOf(HumanSpeechResponseMode.PLAY)),
    DIRECT_QUESTION("필요한 부분을 짧은 질문으로 확인한다", setOf(HumanSpeechResponseMode.FOLLOW_UP)),
    HEDGED_GUESS("확신하지 않고 가능성을 남겨 추측한다", setOf(HumanSpeechResponseMode.SPECULATION)),
    GENTLE_CARE("걱정부터 짧게 보이고 부담 없이 챙긴다", setOf(HumanSpeechResponseMode.CARE)),
    SUPPORTIVE_NUDGE("쉴 것이나 조심할 것을 부드럽게 덧붙인다", setOf(HumanSpeechResponseMode.CARE)),
    ACTION_PROPOSAL("같이 할 다음 행동을 짧게 제안한다", setOf(HumanSpeechResponseMode.COORDINATION)),
    COORDINATION_CHECK("짧게 확인하고 선택이나 시간을 조율한다", setOf(HumanSpeechResponseMode.COORDINATION)),
    TINY_REPLY("두세 글자 안팎으로 바로 반응하고 멈춘다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    SHORT_REPLY("한두 마디로 가볍게 끝낸다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    MEDIUM_REPLY("짧은 문장으로 자연스럽게 풀어 쓴다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    LONGER_REPLY("필요한 말은 짧은 문장으로 한 번 더 풀어 쓴다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    TRAILING_PAUSE("말끝에 가벼운 여운을 남긴다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    CASUAL_SHORT_FORM("친한 대화의 짧은 구어체·축약을 자연스럽게 쓴다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    SOFT_EMOTION_MARKER("가벼운 감정 기호로 톤을 부드럽게 보인다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    SINGLE_BUBBLE("한 말풍선으로 짧게 끝낸다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    MULTI_BUBBLE("두세 말풍선으로 자연스럽게 호흡한다", HumanSpeechResponseMode.entries.toSet(), deliveryOnly = true),
    ;

    fun supports(responseMode: HumanSpeechResponseMode): Boolean = responseMode in supportedModes

    /** 전달 길이·문장부호 같은 표지 자체는 enum별 반응 행동의 근거가 아니다. */
    fun isObservedResponseBehavior(): Boolean = !deliveryOnly

    /** 현재 장면의 의미와 무관하게 안전하게 참고할 수 있는 길이·호흡 표지다. */
    fun isDeliveryOnly(): Boolean = deliveryOnly
}

/** Runtime에 적재할 수 있는 private Speech-style 카드의 승인 경로다. */
enum class HumanSpeechStyleQuality {
    /** fresh verifier가 A~F를 모두 통과한 카드. */
    CURATION_APPROVED,

    /** 사용자가 명시적으로 release한 최소 일반화 human-review preview 카드. */
    USER_RELEASED_REVIEW,
}

/**
 * Speech provider가 받을 수 있는 카드 표면이다.
 *
 * `STYLE_PATTERN`만 runtime provider 표면이다. 카드의 원문 대화·실제 답변은 암호화 감사 저장소에만 남기고,
 * response mode/form/rhythm 같은 닫힌 metadata에서 만든 비식별 리듬 규칙만 provider에 보낸다.
 *
 * `CONTEXT_RESPONSE_PAIR`와 `RESPONSE_ONLY`는 과거 artifact를 읽을 수 있도록 남긴 legacy 값이다. runtime 검색과
 * renderer는 둘 다 fail-closed로 거부한다. `AUDIT_ONLY`는 암호화 감사 저장소에는 남지만 검색·prompt에 절대 쓰이지 않는다.
 */
enum class HumanSpeechStylePromptSurface {
    STYLE_PATTERN,
    CONTEXT_RESPONSE_PAIR,
    RESPONSE_ONLY,
    AUDIT_ONLY,

    ;

    fun isProviderSafe(): Boolean = this == STYLE_PATTERN
}

/** 사람 말투 참고 카드 안의 한 말풍선. 원문 추적 정보는 이 타입에 넣지 않는다. */
data class HumanSpeechStyleBubble(
    val speaker: String,
    val text: String,
) {
    init {
        require(speaker.isNotBlank()) { "human speech style speaker 는 비어 있을 수 없다" }
        require(text.isNotBlank()) { "human speech style bubble 은 비어 있을 수 없다" }
        require(text.length <= MAX_BUBBLE_CHARS) { "human speech style bubble 이 너무 길다" }
    }

    override fun toString(): String = "HumanSpeechStyleBubble(speaker=$speaker, text=<redacted:${text.length} chars>)"

    companion object {
        const val MAX_BUBBLE_CHARS: Int = 350
    }
}

/**
 * Speech 전용 RAG가 보관하는 한 카드.
 *
 * retrievalText는 현재 장면과의 관련도를 계산하는 용도이며 실제 사람 답변의 문구는 의도적으로 넣지 않는다. 답변의
 * 문구가 검색 키가 되면 같은 표현을 그대로 베끼는 쪽으로 검색이 기울 수 있기 때문이다. 답변에서 로컬로 추출한
 * 닫힌 responseRhythm 표지는 별도 보조 벡터에만 넣는다.
 */
data class HumanSpeechStyleExample(
    val exampleId: String,
    val responseMode: HumanSpeechResponseMode,
    val situation: String,
    val styleSignals: List<String>,
    val contextBubbles: List<HumanSpeechStyleBubble>,
    val responseBubbles: List<HumanSpeechStyleBubble>,
    val quality: HumanSpeechStyleQuality,
    val sourceFingerprint: String,
    val consentRevision: String,
    val combinedChars: Int,
    /** 선택된 최소 provider 예시 표면이 안전한지 나타낸다. */
    val promptEligible: Boolean = true,
    val promptSurface: HumanSpeechStylePromptSurface =
        if (promptEligible) HumanSpeechStylePromptSurface.STYLE_PATTERN else HumanSpeechStylePromptSurface.AUDIT_ONLY,
    /** enum 안의 실제 답변 리듬을 보조 관찰값으로 남긴다. 없는 카드도 같은 enum 안에서 의미 검색할 수 있다. */
    val responseMove: HumanSpeechStyleResponseMove? = null,
    /** 카드 앞 대화에서만 로컬로 읽은 닫힌 장면 단서. provider에는 카드 원문 대신 이 enum의 고정 설명만 쓴다. */
    val sceneTraits: List<HumanSpeechSceneTrait> = emptyList(),
    /** 실제 답변에서 관찰한 비의미적 말투 결의 결정적인 primary 목록. provider에는 이 enum의 고정 안내만 전달한다. */
    val providerStyleCues: List<HumanSpeechStyleProviderStyleCue> = emptyList(),
    /** response move의 관찰 경로. runtime은 이 값만으로 세부 행동을 provider에 승격하지 않는다. */
    val responseMoveProvenance: HumanSpeechStyleResponseMoveProvenance =
        if (responseMove !=
            null
        ) {
            HumanSpeechStyleResponseMoveProvenance.HEURISTIC_OBSERVED
        } else {
            HumanSpeechStyleResponseMoveProvenance.NONE
        },
    /** 실제 답변의 질문·맞장구·제안 등 말풍선 형식을 보조 관찰값으로 남긴다. */
    val responseForm: HumanSpeechStyleResponseForm? = null,
    /** 실제 답변의 재사용 가능한 말투·호흡 표지. 원문 답변 문구가 아니다. */
    val responseRhythm: List<HumanSpeechStyleRhythmCue> = emptyList(),
    val embedding: FloatArray,
    val embeddingModel: String,
    /** 답변 리듬 표지만 embed한 보조 벡터. 구형 카드 호환을 위해 비어 있을 수 있다. */
    val rhythmEmbedding: FloatArray = floatArrayOf(),
) {
    init {
        require(exampleId.matches(EXAMPLE_ID)) { "human speech style exampleId 형식이 잘못됐다" }
        require(situation.isNotBlank() && situation.length <= MAX_SITUATION_CHARS) {
            "human speech style situation 형식이 잘못됐다"
        }
        require(styleSignals.size <= MAX_STYLE_SIGNALS && styleSignals.all { it.isNotBlank() && it.length <= MAX_STYLE_SIGNAL_CHARS }) {
            "human speech style styleSignals 형식이 잘못됐다"
        }
        require(contextBubbles.isNotEmpty() && contextBubbles.size <= MAX_CONTEXT_BUBBLES) {
            "human speech style contextBubbles 형식이 잘못됐다"
        }
        require(responseBubbles.isNotEmpty() && responseBubbles.size <= MAX_RESPONSE_BUBBLES) {
            "human speech style responseBubbles 형식이 잘못됐다"
        }
        require(sourceFingerprint.matches(SOURCE_FINGERPRINT)) { "human speech style sourceFingerprint 형식이 잘못됐다" }
        require(consentRevision.matches(CONSENT_REVISION)) { "human speech style consentRevision 형식이 잘못됐다" }
        require(combinedChars in 1..MAX_COMBINED_CHARS) { "human speech style combinedChars 형식이 잘못됐다" }
        require(embedding.isNotEmpty()) { "human speech style embedding 은 비어 있을 수 없다" }
        require(embeddingModel.isNotBlank() && embeddingModel.length <= MAX_EMBEDDING_MODEL_CHARS) {
            "human speech style embeddingModel 형식이 잘못됐다"
        }
        require(
            rhythmEmbedding.isEmpty() ||
                (rhythmEmbedding.size == embedding.size && rhythmEmbedding.all(Float::isFinite)),
        ) {
            "human speech style rhythmEmbedding 형식이 잘못됐다"
        }
        require(responseMove == null || responseMove.responseMode == responseMode) {
            "human speech style responseMove does not match responseMode"
        }
        require(responseMoveProvenance.matches(responseMove)) {
            "human speech style responseMove provenance does not match responseMove"
        }
        require(sceneTraits.size <= MAX_SCENE_TRAITS && sceneTraits.distinct().size == sceneTraits.size) {
            "human speech style sceneTraits 형식이 잘못됐다"
        }
        require(sceneTraits.all { it.responseMode == responseMode }) {
            "human speech style sceneTraits do not match responseMode"
        }
        require(providerStyleCues.size <= MAX_PROVIDER_STYLE_CUES && providerStyleCues.distinct().size == providerStyleCues.size) {
            "human speech style providerStyleCues 형식이 잘못됐다"
        }
        require(providerStyleCues.all { it.responseMode == responseMode }) {
            "human speech style providerStyleCues do not match responseMode"
        }
        require(responseForm == null || responseForm.supports(responseMode)) {
            "human speech style responseForm does not match responseMode"
        }
        require(responseRhythm.size <= MAX_RESPONSE_RHYTHM_CUES && responseRhythm.distinct().size == responseRhythm.size) {
            "human speech style responseRhythm 형식이 잘못됐다"
        }
        require(responseRhythm.all { it.supports(responseMode) }) {
            "human speech style responseRhythm does not match responseMode"
        }
        require(promptEligible == (promptSurface == HumanSpeechStylePromptSurface.STYLE_PATTERN)) {
            "human speech style prompt surface does not match prompt eligibility"
        }
        require(
            when (promptSurface) {
                HumanSpeechStylePromptSurface.STYLE_PATTERN ->
                    providerStyleCues.size == STYLE_PATTERN_PROVIDER_STYLE_CUE_COUNT
                HumanSpeechStylePromptSurface.AUDIT_ONLY -> true
                HumanSpeechStylePromptSurface.CONTEXT_RESPONSE_PAIR,
                HumanSpeechStylePromptSurface.RESPONSE_ONLY,
                -> false
            },
        ) {
            "human speech style prompt surface or primary style cue count is invalid"
        }
    }

    /** 외부 임베딩에는 카드의 닫힌 response metadata만 준다. */
    fun retrievalText(): String =
        humanSpeechStyleRetrievalText(
            responseMode = responseMode,
            responseMove = responseMove,
            sceneTraits = sceneTraits,
            providerStyleCues = providerStyleCues,
            responseForm = responseForm,
            responseRhythm = responseRhythm,
        )

    override fun toString(): String =
        "HumanSpeechStyleExample(exampleId=$exampleId, responseMode=$responseMode, quality=$quality, " +
            "contextBubbles=${contextBubbles.size}, responseBubbles=${responseBubbles.size}, combinedChars=$combinedChars, " +
            "promptEligible=$promptEligible, promptSurface=$promptSurface, responseMove=$responseMove, responseForm=$responseForm, " +
            "sceneTraits=$sceneTraits, providerStyleCues=$providerStyleCues, " +
            "responseMoveProvenance=$responseMoveProvenance, responseRhythm=$responseRhythm, " +
            "embedding=<redacted:${embedding.size} dims>, rhythmEmbedding=<redacted:${rhythmEmbedding.size} dims>)"

    companion object {
        private val EXAMPLE_ID = Regex("human-style-[0-9]{6}")
        const val MAX_SITUATION_CHARS: Int = 240
        const val MAX_STYLE_SIGNALS: Int = 12
        const val MAX_STYLE_SIGNAL_CHARS: Int = 80
        const val MAX_CONTEXT_BUBBLES: Int = 12
        const val MAX_RESPONSE_BUBBLES: Int = 12
        const val MAX_RESPONSE_RHYTHM_CUES: Int = 8
        const val MAX_SCENE_TRAITS: Int = 2
        const val MAX_PROVIDER_STYLE_CUES: Int = 1
        const val STYLE_PATTERN_PROVIDER_STYLE_CUE_COUNT: Int = 1
        const val MAX_COMBINED_CHARS: Int = 350
        const val MAX_EMBEDDING_MODEL_CHARS: Int = 96
        private val SOURCE_FINGERPRINT = Regex("sha256:[0-9a-f]{64}")
        private val CONSENT_REVISION = Regex("[A-Za-z0-9._-]{1,96}")
    }
}

/** 외부 embedding에 보낼 카드 검색면. 원문·일반화 자유 텍스트는 절대 넣지 않는다. */
internal fun humanSpeechStyleRetrievalText(
    responseMode: HumanSpeechResponseMode,
    responseMove: HumanSpeechStyleResponseMove?,
    sceneTraits: List<HumanSpeechSceneTrait> = emptyList(),
    providerStyleCues: List<HumanSpeechStyleProviderStyleCue> = emptyList(),
    responseForm: HumanSpeechStyleResponseForm?,
    responseRhythm: List<HumanSpeechStyleRhythmCue> = emptyList(),
): String =
    buildString {
        appendLine("반응 방식: ${responseMode.name}")
        appendLine("반응 목표: ${responseMode.retrievalDescription}")
        responseMove?.let { appendLine("필요한 반응 리듬: ${it.name}") }
        if (sceneTraits.isNotEmpty()) {
            appendLine("일반화된 장면 단서: ${sceneTraits.joinToString(" · ") { it.retrievalDescription }}")
        }
        if (providerStyleCues.isNotEmpty()) {
            appendLine("관찰된 말투 결: ${providerStyleCues.joinToString(" · ") { it.providerGuidance }}")
        }
        responseForm?.let { appendLine("실제 답변 형식: ${it.name}") }
        if (responseRhythm.isNotEmpty()) {
            appendLine("관찰된 답변 호흡: ${responseRhythm.joinToString(" · ") { it.retrievalDescription }}")
        }
    }.trim()

/** 실제 답변의 원문 없이, 지금 필요한 답변 호흡과 카드의 호흡을 비교하기 위한 보조 검색면이다. */
internal fun humanSpeechStyleRhythmText(
    responseMode: HumanSpeechResponseMode,
    responseRhythm: List<HumanSpeechStyleRhythmCue>,
): String =
    buildString {
        appendLine("반응 방식: ${responseMode.name}")
        appendLine("반응 목표: ${responseMode.retrievalDescription}")
        if (responseRhythm.isNotEmpty()) {
            appendLine("실제 답변 리듬: ${responseRhythm.joinToString("; ") { it.retrievalDescription }}")
        }
    }.trim()

/** 하나의 현재 Speech 장면에 대해 뽑힌 사람 말투 참고 카드. */
data class HumanSpeechStyleMatch(
    val example: HumanSpeechStyleExample,
    val score: Double,
    /**
     * 카드의 세부 반응 행동 중 현재 마지막 발화가 명시적으로 뒷받침한 값만 담는다.
     *
     * null이면 provider에는 enum 기본 안내와 delivery-only 호흡만 보낸다. 카드에서 관찰한 move를 그대로
     * provider 지시로 쓰지 않아, 현재 장면에 없는 이유·상태·시간 같은 사실을 억지로 좁히지 않는다.
     */
    val sceneSupportedResponseMove: HumanSpeechStyleResponseMove? = null,
    /** 현재 마지막 발화의 하나뿐인 닫힌 장면 단서와 카드 앞 장면이 정확히 맞을 때만 채운다. */
    val sceneSupportedSceneTrait: HumanSpeechSceneTrait? = null,
) {
    init {
        require(score in 0.0..1.0) { "human speech style match score 는 0..1 범위여야 한다" }
        require(sceneSupportedResponseMove == null || sceneSupportedResponseMove == example.responseMove) {
            "human speech style scene-supported responseMove does not match the card"
        }
        require(sceneSupportedSceneTrait == null || sceneSupportedSceneTrait in example.sceneTraits) {
            "human speech style scene-supported trait does not match the card"
        }
    }

    override fun toString(): String = "HumanSpeechStyleMatch(exampleId=${example.exampleId}, score=$score)"
}

data class HumanSpeechStyleSelection(
    val matches: List<HumanSpeechStyleMatch>,
) {
    init {
        require(matches.size <= MAX_MATCHES) { "human speech style references 는 최대 $MAX_MATCHES 개다" }
    }

    val isEmpty: Boolean
        get() = matches.isEmpty()

    companion object {
        const val MAX_MATCHES: Int = 2
        val EMPTY = HumanSpeechStyleSelection(emptyList())
    }
}
