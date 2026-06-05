package com.discordassistant.central.global.i18n

/**
 * 핵심 사용자 대면 문구의 타입 안전 접근자. 실제 텍스트의 SSOT 는 `resources/i18n/messages.json` 이고
 * 이 객체는 그 키로 [I18n] 을 조회한다(ko/en/ja). 미지원 언어/키는 en→ko 폴백.
 *
 * 새 문구는 messages.json 에 키를 추가하고 여기에 [Key] 를 더하면 타입 안전하게 쓸 수 있다.
 */
object Messages {
    enum class Key(
        val jsonKey: String,
    ) {
        PRIVACY_NOTICE("privacyNotice"),
        COOLDOWN("cooldown"),
        NO_PROVIDER("noProvider"),
        ADMIN_DENIED("adminDenied"),
    }

    fun get(
        key: Key,
        language: String,
        vararg args: Any?,
    ): String = I18n.get(key.jsonKey, language, *args)
}
