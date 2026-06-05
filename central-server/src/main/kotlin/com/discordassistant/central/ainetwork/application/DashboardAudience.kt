package com.discordassistant.central.ainetwork.application

/**
 * 대시보드 데이터 노출 정책(보안) SSOT. provider 식별자/용량/risk 마스킹 규칙을 한 곳에서 정의해
 * 여러 application/web 매핑이 동일한 redaction 분기를 재사용한다(웹↛application 역의존 제거를 위해
 * web 어댑터에서 application 으로 이동).
 */
enum class DashboardAudience(
    val canSeeProviderIdentity: Boolean,
    val canSeeProviderCapacity: Boolean,
) {
    PUBLIC(false, false),
    PROVIDER(false, true),
    ADMIN(true, true),
    ;

    fun state(value: String): String =
        if (canSeeProviderCapacity) {
            value
        } else if (value.equals("ONLINE", ignoreCase = true)) {
            "available"
        } else {
            "unavailable"
        }

    fun risk(value: String): String =
        if (canSeeProviderCapacity) {
            value
        } else if (value.equals("high", ignoreCase = true) || value.equals("critical", ignoreCase = true)) {
            "protected"
        } else {
            "normal"
        }

    companion object {
        fun from(value: String): DashboardAudience = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PUBLIC
    }
}
