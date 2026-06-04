package com.discordassistant.central.domain

/**
 * 봇이 **완전 지원**하는 UI/응답 언어의 단일 진실 원천(SSOT): 한국어·영어·일본어.
 *
 * 슬래시 옵션 choice, 설정 패널 언어 드롭다운, 문구 SSOT(`i18n/messages.json`)의 로케일 목록이
 * 모두 이 enum 을 기준으로 한다. 코드([code])는 BCP-47 앞부분(소문자)으로 DB/Discord 로케일과 호환.
 * (러시아어 등 그 외 언어는 best-effort 폴백 대상이며 "완전 지원"에는 포함하지 않는다.)
 */
enum class SupportedLanguage(
    val code: String,
    /** 그 언어 사용자에게 그 언어로 보여줄 이름(드롭다운/choice 라벨). */
    val nativeLabel: String,
) {
    KO("ko", "한국어"),
    EN("en", "English"),
    JA("ja", "日本語"),
    ;

    companion object {
        /** 기본 언어(미지정·미지원 폴백). */
        val DEFAULT = KO

        /** 슬래시 옵션/드롭다운 choice 용 (네이티브 라벨, 코드) 목록 — SSOT. */
        fun choices(): List<Pair<String, String>> = entries.map { it.nativeLabel to it.code }

        /** BCP-47/임의 언어 문자열 → 지원 언어. 접두 일치(ko-KR→KO 등), 미지원은 [DEFAULT]. */
        fun fromCode(value: String?): SupportedLanguage {
            val v = value?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { v == it.code || v.startsWith(it.code + "-") } ?: DEFAULT
        }

        /** 지원 언어면 코드, 아니면 null(상위 폴백에 위임할 때 사용). */
        fun codeOrNull(value: String?): String? {
            val v = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { v == it.code || v.startsWith(it.code + "-") }?.code
        }
    }
}
