package com.discordassistant.central.preset.application

import com.discordassistant.central.knowledge.application.KnowledgeSafety
import org.springframework.stereotype.Service

/**
 * 순수 콘텐츠 안전/마스킹 협력자: [PresetRegistryService] 에서 분리한 비밀값 탐지·redaction·문자열
 * 정규화 헬퍼 모음. 모두 부수효과 없는 순수 함수이며 @Transactional 이 없어 호출자 TX 와 무관하게
 * 안전하다. 보안 마스킹/해시/정규식/SSRF 동작은 원본과 1바이트도 다르지 않다.
 */
@Service
class PresetContentSafety {
    fun String.hasSensitiveMaterial(): Boolean = KnowledgeSafety.containsSensitiveMaterial(this) || SECRET_PATTERN.containsMatchIn(this)

    fun String?.publicOptional(maxLength: Int): String? {
        val trimmed = this?.trim()?.ifBlank { null } ?: return null
        if (trimmed.hasSensitiveMaterial()) return REDACTED_PUBLIC_TEXT
        return trimmed.take(maxLength)
    }

    fun String.publicRequired(
        maxLength: Int,
        fallback: String,
    ): String {
        val trimmed = trim().ifBlank { return fallback }
        if (trimmed.hasSensitiveMaterial()) return fallback
        return trimmed.take(maxLength)
    }

    fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun splitLines(value: String?): List<String> =
        value
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    companion object {
        const val REDACTED_PUBLIC_TITLE = "비공개 프리셋"
        const val REDACTED_PUBLIC_TEXT = "[비공개 처리됨]"
        val SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
    }
}
