package com.discordassistant.central.platform.discord.nexa

import java.util.Locale

enum class NexaJudgeMode(
    val wireName: String,
) {
    OFF("off"),
    SHADOW("shadow"),
    FINAL("final"),
    ;

    companion object {
        val DEFAULT: NexaJudgeMode = FINAL
        const val PROPERTY_NAME: String = "central.nexa.judge.mode"

        fun parse(raw: String?): NexaJudgeMode {
            val normalized = raw.orEmpty().trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return DEFAULT
            return entries.firstOrNull { it.wireName == normalized }
                ?: throw IllegalArgumentException(
                    "$PROPERTY_NAME must be one of off, shadow, final: '$raw'",
                )
        }
    }
}
