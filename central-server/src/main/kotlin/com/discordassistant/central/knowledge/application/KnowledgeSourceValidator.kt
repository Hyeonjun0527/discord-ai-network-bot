package com.discordassistant.central.knowledge.application

import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI

/**
 * 지식 소스 검증(타입/크기/민감정보/SSRF·IP 차단) — 순수 협력자(저장소·TX·featureGate 없음).
 *
 * SSRF/IP 차단 판정은 보안 불변식이므로 추출 전 코드와 **1바이트 불변**으로 옮긴다(새 로직 신설 금지).
 * 입력만으로 [SourceValidation] 을 만들며 부작용이 없다 — 별 빈으로 빼도 TX/원자성 영향이 없다.
 */
@Component
class KnowledgeSourceValidator {
    fun validateSource(
        sourceType: String,
        sourceUri: String?,
        contentPreview: String?,
        screenInjection: Boolean = false,
    ): SourceValidation {
        if (sourceType !in ALLOWED_SOURCE_TYPES) {
            return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_TYPE))
        }
        val text = listOf(sourceType, sourceUri.orEmpty(), contentPreview.orEmpty()).joinToString(" ")
        if (contentPreview.orEmpty().length > MAX_CONTENT_PREVIEW_CHARS) {
            return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_TOO_LARGE))
        }
        if (KnowledgeSafety.containsSensitiveMaterial(text)) {
            return SourceValidation("sensitive", KnowledgeSourceStatus.BLOCKED_SENSITIVE)
        }
        if (sourceUri != null) {
            val uriRisk = validateUri(sourceUri)
            if (uriRisk != null) return uriRisk
        }
        // 사용자 생성 콘텐츠(백필)에 남은 프롬프트 인젝션 의심 문구는 자동 색인하지 않고 관리자 검토 큐로 보낸다.
        // (status REVIEW → indexInlineSourceIfPossible 가 isPending 이 아니라 스킵. 관리자는 approveSourceForIndexing 으로 승인 가능.)
        if (screenInjection && KnowledgeSafety.looksRiskyInstruction(contentPreview)) {
            return SourceValidation("review", KnowledgeSourceStatus(KnowledgeSourceStatus.Kind.REVIEW))
        }
        return SourceValidation("normal", KnowledgeSourceStatus.PENDING)
    }

    private fun blocked(kind: KnowledgeSourceStatus.Kind): KnowledgeSourceStatus = KnowledgeSourceStatus(kind)

    private fun validateUri(sourceUri: String): SourceValidation? {
        val uri =
            runCatching { URI(sourceUri) }.getOrNull()
                ?: return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_BAD_URI))
        if (uri.scheme != "https") return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_NON_HTTPS))
        if (uri.rawUserInfo != null) return SourceValidation("sensitive", KnowledgeSourceStatus.BLOCKED_SENSITIVE)
        val host =
            normalizedUriHost(uri)
                ?: return SourceValidation("review", blocked(KnowledgeSourceStatus.Kind.BLOCKED_BAD_URI))
        return when {
            host == "localhost" || host.endsWith(".localhost") -> SourceValidation("ssrf", blocked(KnowledgeSourceStatus.Kind.BLOCKED_SSRF))
            host.endsWith(
                ".local",
            ) ||
                host.endsWith(".internal") -> SourceValidation("ssrf", blocked(KnowledgeSourceStatus.Kind.BLOCKED_SSRF))
            isBlockedAddressLiteral(host) -> SourceValidation("ssrf", blocked(KnowledgeSourceStatus.Kind.BLOCKED_SSRF))
            else -> null
        }
    }

    private fun normalizedUriHost(uri: URI): String? {
        val host =
            uri.host
                ?: uri.rawAuthority
                    ?.substringAfterLast("@")
                    ?.let { authority ->
                        if (authority.startsWith("[")) {
                            authority.substringBefore("]").removePrefix("[")
                        } else {
                            authority.substringBefore(":")
                        }
                    }
        return host
            ?.lowercase()
            ?.removeSurrounding("[", "]")
            ?.trimEnd('.')
            ?.ifBlank { null }
    }

    private fun isBlockedAddressLiteral(host: String): Boolean {
        parseNonCanonicalIpv4Octets(host)?.let { return true }
        parseIpv4Octets(host)?.let { octets ->
            return isBlockedIpv4Octets(octets)
        }
        if (":" !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        val bytes = address.address
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                first == 0xfc ||
                first == 0xfd ||
                (first == 0xfe && (second and 0xc0) == 0x80)
        }
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
    }

    private fun isBlockedIpv4Octets(octets: List<Int>): Boolean {
        val first = octets[0]
        val second = octets[1]
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 0) ||
            (first == 192 && second == 168) ||
            (first == 198 && second in 18..19)
    }

    private fun parseNonCanonicalIpv4Octets(host: String): List<Int>? {
        val parts = host.split(".")
        if (parts.size !in 1..4) return null
        if (!parts.all { IPV4_NUMBER_PART.matches(it) }) return null
        if (parts.size == 4 && parts.none { it.hasNonCanonicalIpv4Part() }) return null
        val numbers = parts.map { parseIpv4NumberPart(it) ?: return null }
        val value =
            when (numbers.size) {
                1 -> numbers[0].takeIf { it <= 0xffff_ffffL }
                2 ->
                    numbers[0].takeIf { it <= 0xff }?.let { first ->
                        numbers[1].takeIf { it <= 0xff_ffff }?.let { rest -> (first shl 24) or rest }
                    }
                3 ->
                    numbers[0].takeIf { it <= 0xff }?.let { first ->
                        numbers[1].takeIf { it <= 0xff }?.let { second ->
                            numbers[2].takeIf { it <= 0xffff }?.let { rest -> (first shl 24) or (second shl 16) or rest }
                        }
                    }
                4 -> numbers.takeIf { values -> values.all { it <= 0xff } }?.fold(0L) { acc, part -> (acc shl 8) or part }
                else -> null
            } ?: return null
        return listOf(
            ((value ushr 24) and 0xff).toInt(),
            ((value ushr 16) and 0xff).toInt(),
            ((value ushr 8) and 0xff).toInt(),
            (value and 0xff).toInt(),
        )
    }

    private fun String.hasNonCanonicalIpv4Part(): Boolean = startsWith("0x", ignoreCase = true) || (length > 1 && startsWith("0"))

    private fun parseIpv4NumberPart(part: String): Long? =
        when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2).toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.drop(1).ifEmpty { "0" }.toLongOrNull(8)
            else -> part.toLongOrNull(10)
        }

    private fun parseIpv4Octets(host: String): List<Int>? {
        if (!IPV4_LITERAL.matches(host)) return null
        if (host.split(".").any { it.hasNonCanonicalIpv4Part() }) return null
        val octets = host.split(".").map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.size == 4 && values.all { it in 0..255 } }
    }

    internal companion object {
        const val MAX_CONTENT_PREVIEW_CHARS = 8_000
        val ALLOWED_SOURCE_TYPES = setOf("file", "link", "text", "faq", "constitution", "preset")
        val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        val IPV4_NUMBER_PART = Regex("""(?i)(?:0x[0-9a-f]+|\d+)""")
    }
}

/** addSource 검증 결과(초기 status + risk). 검증 협력자와 파사드가 공유한다. */
data class SourceValidation(
    val riskLevel: String,
    val initialStatus: KnowledgeSourceStatus,
)
