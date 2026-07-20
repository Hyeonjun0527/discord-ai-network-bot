package com.discordassistant.central.speech.application.port.out

fun interface SpeechInputTracePort {
    fun record(
        traceId: String,
        systemPrompt: String,
        userPrompt: String,
    )

    object Noop : SpeechInputTracePort {
        override fun record(
            traceId: String,
            systemPrompt: String,
            userPrompt: String,
        ) = Unit
    }
}
