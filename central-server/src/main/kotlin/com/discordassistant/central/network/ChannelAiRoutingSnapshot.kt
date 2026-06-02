package com.discordassistant.central.network

import com.discordassistant.central.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.persistence.PresetRevisionEntity
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

data class ChannelAiRoutingSnapshot(
    val responseMode: String = "balanced",
    val preferredModel: String? = null,
    val minQualityTier: String = "standard",
    val maxCandidates: Int = 1,
    val providerTagFilter: String? = null,
    val costGuard: String = "provider_safe",
) {
    fun encode(): String =
        mapOf(
            "responseMode" to responseMode,
            "preferredModel" to preferredModel.orEmpty(),
            "minQualityTier" to minQualityTier,
            "maxCandidates" to maxCandidates.toString(),
            "providerTagFilter" to providerTagFilter.orEmpty(),
            "costGuard" to costGuard,
        ).entries.joinToString("&") { (key, value) -> "${key.encodePart()}=${value.encodePart()}" }

    fun applyTo(
        policy: ChannelAiRoutingPolicyEntity,
        channelAiId: Long,
        now: Instant,
    ) {
        policy.channelAiId = channelAiId
        policy.responseMode = responseMode.trim().ifBlank { "balanced" }
        policy.preferredModel = preferredModel?.trim()?.ifBlank { null }
        policy.minQualityTier = minQualityTier.trim().ifBlank { "standard" }
        policy.maxCandidates = maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        policy.providerTagFilter = providerTagFilter?.trim()?.ifBlank { null }
        policy.costGuard = costGuard.trim().ifBlank { "provider_safe" }
        policy.updatedAt = now
    }

    companion object {
        fun fromRevision(revision: PresetRevisionEntity): ChannelAiRoutingSnapshot =
            ChannelAiRoutingSnapshot(
                responseMode = revision.responseMode,
                preferredModel = revision.preferredModel,
                minQualityTier = revision.minQualityTier,
                maxCandidates = revision.maxCandidates,
                providerTagFilter = revision.providerTagFilter,
                costGuard = revision.costGuard,
            )

        fun decode(raw: String?): ChannelAiRoutingSnapshot? {
            val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val values =
                text
                    .split("&")
                    .mapNotNull { part ->
                        val index = part.indexOf('=')
                        if (index <= 0) null else part.substring(0, index).decodePart() to part.substring(index + 1).decodePart()
                    }.toMap()
            return ChannelAiRoutingSnapshot(
                responseMode = values["responseMode"]?.ifBlank { "balanced" } ?: "balanced",
                preferredModel = values["preferredModel"]?.ifBlank { null },
                minQualityTier = values["minQualityTier"]?.ifBlank { "standard" } ?: "standard",
                maxCandidates = values["maxCandidates"]?.toIntOrNull()?.coerceIn(1, AI_NETWORK_MAX_CANDIDATES) ?: 1,
                providerTagFilter = values["providerTagFilter"]?.ifBlank { null },
                costGuard = values["costGuard"]?.ifBlank { "provider_safe" } ?: "provider_safe",
            )
        }
    }
}

private fun String.encodePart(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

private fun String.decodePart(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)
