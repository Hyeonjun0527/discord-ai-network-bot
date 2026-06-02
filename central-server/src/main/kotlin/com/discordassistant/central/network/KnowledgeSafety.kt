package com.discordassistant.central.network

object KnowledgeSafety {
    private val secretAssignmentPattern = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
    private val sensitivePatterns =
        listOf(
            Regex("(?i)\\b(password|passwd|pwd)\\s*[:=]\\s*\\S+"),
            Regex("(?i)\\b(api[_-]?key|secret|token|bot[_-]?token|private[_-]?key)\\s*[:=]\\s*\\S+"),
            Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
            Regex("(?i)discord[_-]?bot[_-]?token"),
            Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
            Regex("""[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{20,}"""),
        )
    private val sensitiveKeywordPatterns =
        listOf(
            Regex("""(?i)\b(password|passwd|pwd|secret|authorization|bearer)\b"""),
            Regex("""(?i)\b(api[_-]?key|token|bot[_-]?token|private[_-]?key)\s*[:=]\s*\S+"""),
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
            Regex("""sk-[A-Za-z0-9_-]{20,}"""),
        )

    fun containsSensitiveMaterial(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return sensitivePatterns.any { it.containsMatchIn(text) }
    }

    fun looksSensitiveQuery(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return sensitiveKeywordPatterns.any { it.containsMatchIn(text) }
    }

    fun redactReason(value: String): String =
        value
            .trim()
            .replace(secretAssignmentPattern, "[redacted]")
            .take(80)
            .ifBlank { "manual" }
}
