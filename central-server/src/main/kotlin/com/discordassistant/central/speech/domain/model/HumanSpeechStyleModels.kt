package com.discordassistant.central.speech.domain.model

/**
 * 비공개 사람 대화에서 추출한 **말투 참고 카드**의 반응 방식이다.
 *
 * Judge의 판단용 분류가 아니라, Speech가 현재 장면에서 어떤 반응의 리듬을 참고할지 고르는 작은 검색 축이다.
 */
enum class HumanSpeechResponseMode {
    REACTION,
    ALIGNMENT,
    PLAY,
    FOLLOW_UP,
    SPECULATION,
    CARE,
    COORDINATION,
}

/** fresh verifier가 모든 기준을 통과시킨 private curation 승인 카드만 runtime에 허용한다. */
enum class HumanSpeechStyleQuality {
    CURATION_APPROVED,
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
 * retrievalText는 현재 장면과의 관련도를 계산하는 용도이며 실제 사람 답변은 의도적으로 넣지 않는다. 답변의 문구가
 * 검색 키가 되면 같은 표현을 그대로 베끼는 쪽으로 검색이 기울 수 있기 때문이다.
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
    val embedding: FloatArray,
    val embeddingModel: String,
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
    }

    /** 외부 임베딩에는 현재 장면과 맞는지 판단할 정보만 준다. 실제 답변 말풍선은 절대 포함하지 않는다. */
    fun retrievalText(): String =
        buildString {
            appendLine("반응 방식: ${responseMode.name}")
            appendLine("상황: $situation")
            if (styleSignals.isNotEmpty()) appendLine("말투 신호: ${styleSignals.joinToString(", ")}")
            appendLine("앞 대화:")
            contextBubbles.forEach { bubble -> appendLine("- ${bubble.speaker}: ${bubble.text}") }
        }.trim()

    override fun toString(): String =
        "HumanSpeechStyleExample(exampleId=$exampleId, responseMode=$responseMode, quality=$quality, " +
            "contextBubbles=${contextBubbles.size}, responseBubbles=${responseBubbles.size}, combinedChars=$combinedChars, " +
            "embedding=<redacted:${embedding.size} dims>)"

    companion object {
        private val EXAMPLE_ID = Regex("human-style-[0-9]{6}")
        const val MAX_SITUATION_CHARS: Int = 240
        const val MAX_STYLE_SIGNALS: Int = 12
        const val MAX_STYLE_SIGNAL_CHARS: Int = 80
        const val MAX_CONTEXT_BUBBLES: Int = 12
        const val MAX_RESPONSE_BUBBLES: Int = 12
        const val MAX_COMBINED_CHARS: Int = 350
        const val MAX_EMBEDDING_MODEL_CHARS: Int = 96
        private val SOURCE_FINGERPRINT = Regex("sha256:[0-9a-f]{64}")
        private val CONSENT_REVISION = Regex("[A-Za-z0-9._-]{1,96}")
    }
}

/** 하나의 현재 Speech 장면에 대해 뽑힌 사람 말투 참고 카드. */
data class HumanSpeechStyleMatch(
    val example: HumanSpeechStyleExample,
    val score: Double,
) {
    init {
        require(score in 0.0..1.0) { "human speech style match score 는 0..1 범위여야 한다" }
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
