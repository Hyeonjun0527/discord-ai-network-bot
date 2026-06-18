package com.discordassistant.central.platform.discord

import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

/**
 * 슬래시 명령 로컬라이제이션(차수 11 i18n UX). 명령 메뉴를 **보는 사람의 디스코드 클라이언트 언어**로 표시.
 * 기본(default) 설명/이름은 한국어, 영어/일본어/러시아어 로컬라이제이션을 더한다.
 * - 이름: 기본 ascii(ask 등) + 한국어/일본어/러시아어 이름 로컬라이제이션(클라이언트 언어로 자동 표시).
 * - 설명: 한국어(기본) + 영어 + 일본어 + 러시아어.
 *
 * 완전 지원 언어(ko/en/ja)는 모든 명령에 이름·설명이 있어야 한다(가드: CommandLocJaCoverageTest).
 * 러시아어(ru)는 best-effort(이름은 일부만, 설명은 전부). 표에 없는 명령은 한국어 기본만 유지.
 *
 * 슬래시 명령은 유저가 자주 쓰는 단순 명령(≤10)만 남기고 서버 관리·운영은 웹 대시보드로 이관했다
 * (SlashCommandCatalog 참고). 이 표는 그 등록 명령과 1:1로 일치해야 한다(가드: CommandLocCoverageDriftTest).
 */
object CommandLoc {
    private data class L(
        val nameKo: String,
        val nameJa: String,
        val nameRu: String? = null,
        val descEn: String,
        val descJa: String,
        val descRu: String,
    )

    private val EN_LOCALES = listOf(DiscordLocale.ENGLISH_US, DiscordLocale.ENGLISH_UK)

    // 명령 기본이름 → (한국어 이름, 일본어 이름, 러시아어 이름, 영어 설명, 일본어 설명, 러시아어 설명)
    private val TABLE: Map<String, L> =
        mapOf(
            "ask" to
                L(
                    "질문",
                    "質問",
                    "спросить",
                    "Ask AI (free cloud by default, local when a community provider is connected)",
                    "AIに質問します(既定は無料クラウド・ローカル接続時はローカル)",
                    "Спросить ИИ (по умолчанию бесплатное облако, локально при подключении)",
                ),
            "imagine" to
                L(
                    "그림",
                    "画像",
                    "картинка",
                    "Generate an image (free cloud Stable Diffusion by default, local when connected)",
                    "画像を生成します(既定は無料クラウドStable Diffusion・ローカル接続時はローカル)",
                    "Сгенерировать изображение (бесплатное облако по умолчанию)",
                ),
            "menu" to
                L(
                    "메뉴",
                    "メニュー",
                    "меню",
                    "Open the start panel (ask/contribute/settings/help)",
                    "スタートパネルを開きます(質問・貢献・設定・ヘルプ)",
                    "Открыть стартовую панель",
                ),
            "help" to L("도움말", "ヘルプ", "помощь", "Show the full command help", "コマンドの総合ヘルプを見ます", "Показать справку по командам"),
            "settings" to
                L(
                    "설정",
                    "設定",
                    "настройки",
                    "Get a link to the settings & admin dashboard (web)",
                    "設定・管理ダッシュボード(ウェブ)へのリンクを表示します",
                    "Ссылка на панель настроек и управления (веб)",
                ),
            "my-usage" to
                L("내사용량", "使用量", "моё-использование", "Check your usage today", "今日の自分の使用量を確認します", "Ваше использование за сегодня"),
            "nia" to
                L(
                    "니아",
                    "ニア",
                    "ния",
                    "Show your affinity with Nia",
                    "ニアとの親密度を見ます",
                    "Показать близость с Нией",
                ),
            "privacy" to
                L(
                    "프라이버시",
                    "プライバシー",
                    "конфиденциальность",
                    "AI processing & privacy notice",
                    "AI処理・プライバシーのご案内",
                    "Обработка ИИ и конфиденциальность",
                ),
            "provider-join" to
                L(
                    "프로바이더참여",
                    "プロバイダー参加",
                    "стать-провайдером",
                    "Join as a provider (manage afterwards in the desktop app)",
                    "プロバイダーとして参加します(以降の運用はデスクトップアプリ)",
                    "Стать провайдером (далее управление в десктоп-приложении)",
                ),
            "provider-status" to
                L("내상태", "自分の状態", "мой-статус", "Check my provider status", "自分のプロバイダー状態を確認します", "Мой статус провайдера"),
        )

    /** 표에 정의된 모든 명령 기본이름(가드/테스트용). */
    val commands: Set<String> get() = TABLE.keys

    /** 완전 지원 언어(ko/en/ja)에서 명령 이름이 비어있지 않은지 점검용(en 이름은 기본 ascii 라 항상 존재). */
    fun hasFullJa(base: String): Boolean = TABLE[base]?.let { it.nameJa.isNotBlank() && it.descJa.isNotBlank() } ?: false

    /**
     * 명령 표시 이름을 보는 사람의 클라이언트 로케일에 맞춰 반환(슬래시 메뉴에 보이는 이름과 일치시키기 위함).
     * 도움말/안내 문구에서 `/명령` 을 적을 때 사용. 표에 없거나 미지원 로케일은 기본 ascii.
     */
    fun localName(
        base: String,
        locale: DiscordLocale,
    ): String {
        val l = TABLE[base] ?: return base
        return when (locale) {
            DiscordLocale.KOREAN -> l.nameKo
            DiscordLocale.JAPANESE -> l.nameJa
            DiscordLocale.RUSSIAN -> l.nameRu ?: base
            else -> base
        }
    }

    /** 슬래시 명령에 이름/설명 로컬라이제이션 적용(있는 항목만). 기본 설명은 한국어로 이미 설정됨. */
    fun localize(cmd: SlashCommandData) {
        val l = TABLE[cmd.name] ?: return
        cmd.setNameLocalization(DiscordLocale.KOREAN, l.nameKo)
        cmd.setNameLocalization(DiscordLocale.JAPANESE, l.nameJa)
        l.nameRu?.let { cmd.setNameLocalization(DiscordLocale.RUSSIAN, it) }
        cmd.setDescriptionLocalization(DiscordLocale.KOREAN, cmd.description) // 기본=한국어 명시
        EN_LOCALES.forEach { cmd.setDescriptionLocalization(it, l.descEn) }
        cmd.setDescriptionLocalization(DiscordLocale.JAPANESE, l.descJa)
        cmd.setDescriptionLocalization(DiscordLocale.RUSSIAN, l.descRu)
    }
}
