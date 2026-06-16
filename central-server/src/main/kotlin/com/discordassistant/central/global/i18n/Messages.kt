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
        SETTINGS_LINK_TITLE("settingsLinkTitle"),
        SETTINGS_LINK_DESCRIPTION("settingsLinkDescription"),
        SETTINGS_LINK_HOME_BUTTON("settingsLinkHomeButton"),
        SETTINGS_LINK_CHANNEL_BUTTON("settingsLinkChannelButton"),
        SETTINGS_LINK_NO_BASE_URL("settingsLinkNoBaseUrl"),
        SETTINGS_LINK_USER_TITLE("settingsLinkUserTitle"),
        SETTINGS_LINK_USER_DESCRIPTION("settingsLinkUserDescription"),
        ONBOARDING_ADMIN_ONLY("onboardingAdminOnly"),
        HELP_TITLE("helpTitle"),
        HELP_INTRO("helpIntro"),
        HELP_SECTION_USER("helpSectionUser"),
        HELP_SECTION_PROVIDER("helpSectionProvider"),
        HELP_SECTION_ADMIN("helpSectionAdmin"),
        HELP_SENSITIVE_NOTICE("helpSensitiveNotice"),
        HELP_LABEL_USER("helpLabelUser"),
        HELP_LABEL_PROVIDER("helpLabelProvider"),
        HELP_LABEL_ADMIN("helpLabelAdmin"),
        HELP_FOOTER_SENSITIVE("helpFooterSensitive"),
        ADMIN_ONLY_SHORT("adminOnlyShort"),
        CHANNEL_PROFILE_ADMIN_ONLY("channelProfileAdminOnly"),
        UNKNOWN_ACTION("unknownAction"),
        UNKNOWN_SELECTION("unknownSelection"),
        UNKNOWN_ONBOARDING_ACTION("unknownOnboardingAction"),
        ASK_REJECTED("askRejected"),
        ASK_FAILED("askFailed"),
        PROVIDER_PAUSED("providerPaused"),
        PROVIDER_RESUMED("providerResumed"),
        PROVIDER_LEFT("providerLeft"),
        PROVIDER_NO_AGENT("providerNoAgent"),
    }

    fun get(
        key: Key,
        language: String,
        vararg args: Any?,
    ): String = I18n.get(key.jsonKey, language, *args)
}
