package com.discordassistant.central.speech.application.port.out

/** AI judge가 외부 검증을 요구한 발화만 웹 근거로 보강하는 speech 아웃바운드 경계다. */
fun interface SpeechFactualGroundingPort {
    fun verify(query: String): SpeechFactualGrounding

    data object Noop : SpeechFactualGroundingPort {
        override fun verify(query: String): SpeechFactualGrounding = SpeechFactualGrounding.unavailable()
    }
}

data class SpeechFactualGrounding(
    val evidence: String?,
    val sourceRefs: List<String> = emptyList(),
) {
    init {
        require(evidence == null || evidence.isNotBlank()) { "grounding evidence 는 공백일 수 없다" }
        require(sourceRefs.size <= MAX_SOURCES) { "grounding source 는 최대 $MAX_SOURCES 개다" }
        require(sourceRefs.none(String::isBlank)) { "grounding source 는 공백일 수 없다" }
    }

    val verified: Boolean get() = !evidence.isNullOrBlank() && sourceRefs.isNotEmpty()

    companion object {
        const val MAX_SOURCES: Int = 8

        fun unavailable(): SpeechFactualGrounding = SpeechFactualGrounding(evidence = null)
    }
}
