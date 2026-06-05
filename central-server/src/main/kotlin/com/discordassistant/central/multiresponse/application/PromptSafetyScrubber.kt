package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.knowledge.application.KnowledgeSafety
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID

/**
 * 순수 프롬프트/요청 안전 스크러버: [MultiResponseService] 에서 분리한 민감 프롬프트 탐지·request id
 * 정규화·redaction 헬퍼. 부수효과 없는 순수 함수이며 @Transactional 이 없어 호출자 TX 와 무관하다.
 * 보안 마스킹/해시(SHA-256)/정규식은 원본과 1바이트도 다르지 않다.
 */
@Service
class PromptSafetyScrubber {
    fun sanitizeText(
        value: String?,
        maxLength: Int,
    ): String? =
        value
            ?.trim()
            ?.replace(SECRET_PATTERN, "[redacted]")
            ?.take(maxLength)
            ?.ifBlank { null }

    fun String?.isSensitivePrompt(): Boolean {
        val text = this?.trim().orEmpty()
        if (text.isBlank()) return false
        return SENSITIVE_PROMPT_PATTERNS.any { it.containsMatchIn(text) }
    }

    fun sanitizeRequestId(requestId: String): String {
        val trimmed = requestId.trim().ifBlank { newRequestId() }
        if (trimmed.hasSensitiveMaterial()) return "redacted-${sha256(trimmed).take(12)}"
        return trimmed.take(160)
    }

    fun String.hasSensitiveMaterial(): Boolean = KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
        val SENSITIVE_PROMPT_PATTERNS =
            listOf(
                Regex("""(?i)\b(password|passwd|pwd|secret)\b"""),
                Regex("(?i)(api[_-]?key|bot[_-]?token|discord[_-]?bot[_-]?token|private[_-]?key|access[_-]?token)"),
                Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
                Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
            )
    }
}
