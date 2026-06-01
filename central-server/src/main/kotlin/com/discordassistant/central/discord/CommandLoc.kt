package com.discordassistant.central.discord

import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

/**
 * 슬래시 명령 로컬라이제이션(차수 11 i18n UX). 명령 메뉴를 **보는 사람의 디스코드 클라이언트 언어**로 표시.
 * 기본(default) 설명/이름은 한국어, 영어/러시아어 로컬라이제이션을 더한다.
 * - 이름: 기본 ascii(ask 등) + 한국어/러시아어 이름 로컬라이제이션(클라이언트 언어로 자동 표시).
 * - 설명: 한국어(기본) + 영어 + 러시아어.
 * 표에 없는 명령은 한국어 기본만 유지(점진 확장 가능). dispatch 는 항상 기본 이름으로 동작(영향 없음).
 */
object CommandLoc {
    private data class L(
        val nameKo: String,
        val nameRu: String? = null,
        val descEn: String,
        val descRu: String,
    )

    private val EN_LOCALES = listOf(DiscordLocale.ENGLISH_US, DiscordLocale.ENGLISH_UK)

    // 명령 기본이름 → (한국어 이름, 러시아어 이름, 영어 설명, 러시아어 설명)
    private val TABLE: Map<String, L> =
        mapOf(
            "ask" to L("질문", "спросить", "Ask the community local AI", "Спросить локальный ИИ сообщества"),
            "models" to L("모델", "модели", "Check available model levels", "Доступные уровни моделей"),
            "catalog" to L("모델목록", "каталог", "List models offered in this server", "Список моделей этого сервера"),
            "my-usage" to L("내사용량", "моё-использование", "Check your usage today", "Ваше использование за сегодня"),
            "contributions" to L("기여순위", "вклад", "Community contribution leaderboard", "Таблица вклада сообщества"),
            "community-stats" to L("커뮤니티통계", "статистика", "Anonymous community stats", "Анонимная статистика сообщества"),
            "fairness" to L("공정성", null, "Fairness report (admin)", "Отчёт о справедливости (админ)"),
            "privacy" to L("프라이버시", "конфиденциальность", "AI processing & privacy notice", "Обработка ИИ и конфиденциальность"),
            "help" to L("도움말", "помощь", "Show the full command help", "Показать справку по командам"),
            "welcome" to L("환영말", null, "Show the server welcome message", "Показать приветствие сервера"),
            "llm-welcome-set" to L("환영말설정", null, "Set the welcome message (admin)", "Задать приветствие (админ)"),
            "provider-join" to
                L("프로바이더참여", "стать-провайдером", "Join as a provider (contribute your PC)", "Стать провайдером (подключить свой ПК)"),
            "provider-pause" to L("수신정지", null, "Pause receiving requests", "Приостановить приём запросов"),
            "provider-resume" to L("수신재개", null, "Resume receiving requests", "Возобновить приём запросов"),
            "provider-leave" to L("풀나가기", null, "Leave the pool", "Покинуть пул"),
            "provider-status" to L("내상태", "мой-статус", "Check my provider status", "Мой статус провайдера"),
            "provider-models" to L("내모델설정", null, "Set offered models (usually auto-detected)", "Указать модели (обычно авто-определение)"),
            "provider-limit" to L("내한도설정", null, "Set per-model limits", "Лимиты по модели"),
            "provider-scope" to L("내허용범위", null, "Set model access scope", "Область доступа к модели"),
            "provider-schedule" to L("내가용시간", null, "Set availability hours (UTC, auto-pause outside)", "Часы доступности (UTC)"),
            "llm-allow-channel" to L("채널허용", null, "Allow a channel for LLM (admin)", "Разрешить канал (админ)"),
            "llm-deny-channel" to L("채널금지", null, "Disallow a channel for LLM (admin)", "Запретить канал (админ)"),
            "llm-role-policy" to L("역할정책", null, "Set per-role allowed level (admin)", "Политика по ролям (админ)"),
            "llm-guild-defaults" to L("서버기본값", null, "Set default model/language (admin)", "Модель/язык по умолчанию (админ)"),
            "llm-channel-profile" to L("채널프로필", null, "Set this channel's AI answer profile (admin)", "Профиль ИИ канала (админ)"),
            "providers" to L("프로바이더목록", null, "View the provider pool (admin)", "Список пула провайдеров (админ)"),
            "provider-approve" to L("프로바이더승인", null, "Approve a provider (admin)", "Одобрить провайдера (админ)"),
            "provider-remove" to L("프로바이더제거", null, "Remove a provider (admin)", "Удалить провайдера (админ)"),
            "llm-block" to L("사용자차단", null, "Block a user (admin)", "Заблокировать пользователя (админ)"),
            "llm-unblock" to L("차단해제", null, "Unblock a user (admin)", "Разблокировать пользователя (админ)"),
            "menu" to L("메뉴", "меню", "Open the start panel (ask/contribute/settings/help)", "Открыть стартовую панель"),
            "llm-settings" to L("설정", "настройки", "Open the settings panel (admin)", "Открыть панель настроек (админ)"),
            "ai-network-map" to L("네트워크지도", null, "Show the AI network map (admin)", "Карта ИИ-сети (админ)"),
            "ai-network-check" to L("네트워크점검", null, "Show the AI network launch checklist (admin)", "Проверка ИИ-сети (админ)"),
            "ai-preset-catalog" to L("프리셋목록", null, "Browse shared AI presets", "Список пресетов ИИ"),
            "ai-preset-import" to L("프리셋가져오기", null, "Import a shared preset into this channel", "Импорт пресета"),
            "ai-preset-like" to L("프리셋좋아요", null, "Like a shared AI preset", "Лайк пресета"),
            "bot-permissions" to L("봇권한", null, "Check bot permissions and mention setup (admin)", "Права бота (админ)"),
            "ask-long" to L("긴질문", null, "Enter a long question via modal", "Длинный вопрос (модальное окно)"),
        )

    /**
     * 명령 표시 이름을 보는 사람의 클라이언트 로케일에 맞춰 반환(슬래시 메뉴에 보이는 이름과 일치시키기 위함).
     * 도움말/안내 문구에서 `/명령` 을 적을 때 사용 — ko 면 한국어 이름, ru 면 러시아어 이름, 그 외는 기본 ascii.
     * 표에 없는 명령은 기본 이름을 그대로 돌려준다.
     */
    fun localName(
        base: String,
        locale: DiscordLocale,
    ): String {
        val l = TABLE[base] ?: return base
        return when (locale) {
            DiscordLocale.KOREAN -> l.nameKo
            DiscordLocale.RUSSIAN -> l.nameRu ?: base
            else -> base
        }
    }

    /** 슬래시 명령에 이름/설명 로컬라이제이션 적용(있는 항목만). 기본 설명은 한국어로 이미 설정됨. */
    fun localize(cmd: SlashCommandData) {
        val l = TABLE[cmd.name] ?: return
        cmd.setNameLocalization(DiscordLocale.KOREAN, l.nameKo)
        l.nameRu?.let { cmd.setNameLocalization(DiscordLocale.RUSSIAN, it) }
        cmd.setDescriptionLocalization(DiscordLocale.KOREAN, cmd.description) // 기본=한국어 명시
        EN_LOCALES.forEach { cmd.setDescriptionLocalization(it, l.descEn) }
        cmd.setDescriptionLocalization(DiscordLocale.RUSSIAN, l.descRu)
    }
}
