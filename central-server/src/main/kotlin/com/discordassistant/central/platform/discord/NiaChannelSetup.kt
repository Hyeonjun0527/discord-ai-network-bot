package com.discordassistant.central.platform.discord

import com.discordassistant.central.global.i18n.I18n

/**
 * "니아 채널 자동 만들기" 버튼/슬래시(`setup-channels`)의 **순수 로직** SSOT.
 * JDA I/O 가 없는 결정(권한 분기·멱등 판정·카테고리/채널 이름·핀 가이드 문구)만 담아 단위 테스트한다.
 * 실제 JDA 채널 생성/핀/응답은 [NiaChannelSetupHandler] 가 이 결정을 소비해 수행한다.
 *
 * 카미봇의 "채널 자동으로 만들기"처럼 버튼 한 번에 카테고리/채널을 만들고 안내를 고정한다.
 * 문구 SSOT 는 i18n/messages.json(bot 섹션) — 여기선 [I18n] 키만 참조한다(드리프트 방지).
 */
object NiaChannelSetup {
    /** 컴포넌트 id(버튼 클릭 디스패치 키). 온보딩(`onboard:*`)과는 별개. */
    const val COMPONENT_ID = "setup:nia-channels"

    /** 슬래시 명령 이름(폴백 — systemChannel 없거나 버튼이 사라졌을 때 관리자가 직접 호출). */
    const val COMMAND_NAME = "setup-channels"

    /** ai채팅 채널에 부여하는 니아 채널 AI 프로필 표시 이름(웹훅 페르소나). */
    const val NIA_PROFILE_NAME = "니아"

    private const val MANAGED_TOPIC_PREFIX = "nia-managed-channel"

    /** 권한 검사 결과 — 핸들러가 이 분기로 ephemeral 응답/생성을 결정한다. */
    enum class PermissionDecision {
        /** 클릭/호출자가 길드 관리자가 아님 → 거부. */
        NOT_ADMIN,

        /** 봇에 채널 관리 권한이 없음 → 거부. */
        BOT_MISSING_PERMISSION,

        /** 생성 진행 가능. */
        ALLOWED,
    }

    /**
     * 권한 분기 결정(순수). 관리자(MANAGE_SERVER 또는 MANAGE_CHANNELS)가 아니면 [PermissionDecision.NOT_ADMIN],
     * 봇이 채널 관리 권한이 없으면 [PermissionDecision.BOT_MISSING_PERMISSION], 둘 다 충족하면 [PermissionDecision.ALLOWED].
     */
    fun decide(
        callerIsAdmin: Boolean,
        botCanManageChannels: Boolean,
    ): PermissionDecision =
        when {
            !callerIsAdmin -> PermissionDecision.NOT_ADMIN
            !botCanManageChannels -> PermissionDecision.BOT_MISSING_PERMISSION
            else -> PermissionDecision.ALLOWED
        }

    /**
     * 멱등 판정(순수). 이미 "니아 기능 채널" 카테고리가 있으면 true → 새로 만들지 않고 안내만 한다.
     * 비교는 대소문자 무시 + 공백 trim(디스코드가 카테고리 이름을 다듬을 수 있어 보수적으로).
     */
    fun alreadySetUp(
        existingCategoryNames: Collection<String>,
        language: String = I18n.DEFAULT,
    ): Boolean {
        val targets = (featureCategoryNameCandidates() + featureCategoryName(language)).map { it.normalizedDiscordName() }.toSet()
        return existingCategoryNames.any { it.normalizedDiscordName() in targets }
    }

    fun featureCategoryName(language: String = I18n.DEFAULT): String = I18n.get("niaSetupCategoryFeature", language)

    fun featureCategoryNameCandidates(): Set<String> = localizedNames(::featureCategoryName)

    fun voiceCategoryName(language: String = I18n.DEFAULT): String = I18n.get("niaSetupCategoryVoice", language)

    fun chatChannelName(language: String = I18n.DEFAULT): String = I18n.get("niaSetupChannelChat", language)

    fun chatChannelNameCandidates(): Set<String> = localizedNames(::chatChannelName)

    fun imageChannelName(language: String = I18n.DEFAULT): String = I18n.get("niaSetupChannelImage", language)

    fun imageChannelNameCandidates(): Set<String> = localizedNames(::imageChannelName)

    fun memberChannelName(language: String = I18n.DEFAULT): String = I18n.get("niaSetupChannelMember", language)

    fun memberChannelNameCandidates(): Set<String> = localizedNames(::memberChannelName)

    fun voiceChannelName(language: String = I18n.DEFAULT): String = I18n.get("niaSetupChannelVoice", language)

    /** ai채팅 고정(pin) 가이드 — 니아 톤의 사용법 + 예시 질문. */
    fun chatGuide(language: String = I18n.DEFAULT): String = I18n.get("niaSetupGuideChat", language)

    /** ai그림 고정(pin) 가이드 — /그림 사용법 + 좋은 프롬프트 팁. */
    fun imageGuide(language: String = I18n.DEFAULT): String = I18n.get("niaSetupGuideImage", language)

    /** 니아수다 고정(pin) 가이드 — 사람처럼 끼어드는 참여 채널 안내. */
    fun memberGuide(language: String = I18n.DEFAULT): String = I18n.get("niaSetupGuideMember", language)

    fun channelMarker(role: String): String = "$MANAGED_TOPIC_PREFIX:$role"

    private fun localizedNames(resolve: (String) -> String): Set<String> = I18n.LOCALES.map(resolve).toSet()

    private fun String.normalizedDiscordName(): String = trim().lowercase()
}
