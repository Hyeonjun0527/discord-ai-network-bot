package com.discordassistant.central.discord

import com.discordassistant.central.domain.SupportedLanguage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.dv8tion.jda.api.interactions.DiscordLocale
import org.springframework.core.io.ClassPathResource

/**
 * 런타임 문구 i18n 조회 facade. 문구 SSOT 는 `resources/i18n/messages.json` 이고, 이 객체는 시작 시 한 번
 * 로드해 `key → (lang → text)` 로 보관한다. 지원 언어는 [SupportedLanguage](ko/en/ja).
 *
 * - [get]: 키+언어로 조회하고 `{0},{1}...` 자리표시자를 인자로 치환. 미지원 언어/키는 en→ko→키 폴백.
 * - [resolveOrNull]: Discord 클라이언트 로케일 → 지원 언어 코드(미지원이면 null → 상위 폴백에 위임).
 */
object I18n {
    val LOCALES: List<String> = SupportedLanguage.entries.map { it.code }
    const val DEFAULT: String = "ko"

    // key -> (langCode -> text). `_` 로 시작하는 메타 키는 제외.
    private val table: Map<String, Map<String, String>> = load()

    private fun load(): Map<String, Map<String, String>> {
        val mapper = jacksonObjectMapper()
        val raw =
            ClassPathResource("i18n/messages.json").inputStream.use {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(it, Map::class.java) as Map<String, Any?>
            }
        return raw
            .filterKeys { !it.startsWith("_") }
            .mapValues { (_, v) ->
                (v as Map<*, *>).entries.associate { (k, t) -> k.toString() to t.toString() }
            }
    }

    /** SSOT 에 정의된 모든 문구 키. */
    val keys: Set<String> get() = table.keys

    /** 특정 키의 특정 언어 텍스트(폴백 없이). 없으면 null — 가드/테스트용. */
    fun rawOrNull(
        key: String,
        lang: String,
    ): String? = table[key]?.get(lang)

    /**
     * 문구 조회 + `{0},{1}...` 치환. 미지원 언어/키는 en→ko→키 순 폴백.
     */
    fun get(
        key: String,
        lang: String?,
        vararg args: Any?,
    ): String {
        val byLang = table[key] ?: return key
        val code = SupportedLanguage.fromCode(lang).code
        val text = byLang[code] ?: byLang["en"] ?: byLang[DEFAULT] ?: byLang.values.firstOrNull() ?: return key
        return interpolate(text, args)
    }

    private fun interpolate(
        text: String,
        args: Array<out Any?>,
    ): String {
        if (args.isEmpty()) return text
        var out = text
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a?.toString() ?: "") }
        return out
    }

    /** Discord 클라이언트 로케일 → 지원 언어 코드. 지원하지 않으면 null(길드 기본 등 상위 폴백에 위임). */
    fun resolveOrNull(locale: DiscordLocale?): String? =
        when (locale) {
            DiscordLocale.KOREAN -> "ko"
            DiscordLocale.JAPANESE -> "ja"
            DiscordLocale.ENGLISH_US, DiscordLocale.ENGLISH_UK -> "en"
            else -> null
        }
}
