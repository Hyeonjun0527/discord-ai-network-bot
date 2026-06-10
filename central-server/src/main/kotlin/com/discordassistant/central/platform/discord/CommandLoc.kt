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
            "ask" to L("질문", "質問", "спросить", "Ask the community local AI", "コミュニティのローカルAIに質問します", "Спросить локальный ИИ сообщества"),
            "imagine" to
                L(
                    "그림",
                    "画像",
                    "картинка",
                    "Generate an image (community local Stable Diffusion)",
                    "画像を生成します(コミュニティのローカルStable Diffusion)",
                    "Сгенерировать изображение",
                ),
            "forward-channel" to
                L(
                    "그림채널",
                    "画像チャンネル",
                    "канал-изображений",
                    "Set the channel for ComfyUI web-generated images (admin)",
                    "ComfyUIウェブで生成した画像を転送するチャンネルを設定します(管理者)",
                    "Установить канал для изображений из ComfyUI (админ)",
                ),
            "free-ask" to
                L(
                    "무료질문",
                    "無料質問",
                    "бесплатный-вопрос",
                    "Ask the free cloud AI (Gemini)",
                    "無料クラウドAI(Gemini)に質問します",
                    "Спросить бесплатный облачный ИИ (Gemini)",
                ),
            "models" to L("모델", "モデル", "модели", "Check available model levels", "利用可能なモデル水準を確認します", "Доступные уровни моделей"),
            "catalog" to
                L("모델목록", "モデル一覧", "каталог", "List models offered in this server", "このサーバーで提供中のモデル一覧を見ます", "Список моделей этого сервера"),
            "my-usage" to
                L("내사용량", "使用量", "моё-использование", "Check your usage today", "今日の自分の使用量を確認します", "Ваше использование за сегодня"),
            "contributions" to
                L("기여순위", "貢献ランキング", "вклад", "Community contribution leaderboard", "コミュニティ貢献ランキングを見ます", "Таблица вклада сообщества"),
            "community-stats" to
                L("커뮤니티통계", "統計", "статистика", "Anonymous community stats", "匿名のコミュニティ統計を見ます", "Анонимная статистика сообщества"),
            "level" to
                L(
                    "레벨",
                    "レベル",
                    "уровень",
                    "Show this server AI's activity level/XP",
                    "このサーバーAIの活動レベル/経験値を見ます",
                    "Уровень активности ИИ сервера",
                ),
            "fairness" to L("공정성", "公正性", null, "Fairness report (admin)", "公平性レポートを見ます(管理者)", "Отчёт о справедливости (админ)"),
            "privacy" to
                L(
                    "프라이버시",
                    "プライバシー",
                    "конфиденциальность",
                    "AI processing & privacy notice",
                    "AI処理・プライバシーのご案内",
                    "Обработка ИИ и конфиденциальность",
                ),
            "help" to L("도움말", "ヘルプ", "помощь", "Show the full command help", "コマンドの総合ヘルプを見ます", "Показать справку по командам"),
            "welcome" to L("환영말", "ようこそ", null, "Show the server welcome message", "サーバーの歓迎メッセージを見ます", "Показать приветствие сервера"),
            "llm-welcome-set" to
                L("환영말설정", "ようこそ設定", null, "Set the welcome message (admin)", "歓迎メッセージを設定します(管理者)", "Задать приветствие (админ)"),
            "provider-join" to
                L(
                    "프로바이더참여",
                    "プロバイダー参加",
                    "стать-провайдером",
                    "Join as a provider (contribute your PC)",
                    "プロバイダーとして参加します(自分のPCを提供)",
                    "Стать провайдером (подключить свой ПК)",
                ),
            "provider-pause" to L("수신정지", "受信停止", null, "Pause receiving requests", "リクエストの受信を一時停止します", "Приостановить приём запросов"),
            "provider-resume" to L("수신재개", "受信再開", null, "Resume receiving requests", "リクエストの受信を再開します", "Возобновить приём запросов"),
            "provider-leave" to L("풀나가기", "プール退出", null, "Leave the pool", "プールから退出します", "Покинуть пул"),
            "provider-status" to L("내상태", "自分の状態", "мой-статус", "Check my provider status", "自分のプロバイダー状態を確認します", "Мой статус провайдера"),
            "provider-models" to
                L(
                    "내모델설정",
                    "モデル設定",
                    null,
                    "Set offered models (usually auto-detected)",
                    "提供モデルを指定します(通常は自動検出)",
                    "Указать модели (обычно авто-определение)",
                ),
            "provider-limit" to L("내한도설정", "上限設定", null, "Set per-model limits", "モデル別の上限を設定します", "Лимиты по модели"),
            "provider-schedule" to
                L(
                    "내가용시간",
                    "稼働時間",
                    null,
                    "Set availability hours (UTC, auto-pause outside)",
                    "稼働時間を設定します(UTC、時間外は自動停止)",
                    "Часы доступности (UTC)",
                ),
            "llm-allow-channel" to
                L("채널허용", "チャンネル許可", null, "Allow a channel for LLM (admin)", "LLM利用チャンネルを許可します(管理者)", "Разрешить канал (админ)"),
            "llm-deny-channel" to
                L("채널금지", "チャンネル禁止", null, "Disallow a channel for LLM (admin)", "LLM利用チャンネルを禁止します(管理者)", "Запретить канал (админ)"),
            "llm-role-policy" to
                L("역할정책", "ロール方針", null, "Set per-role allowed level (admin)", "ロール別の許可水準を設定します(管理者)", "Политика по ролям (админ)"),
            "llm-guild-defaults" to
                L(
                    "서버기본값",
                    "サーバー既定値",
                    null,
                    "Set default model/language (admin)",
                    "サーバー既定のモデル/言語を設定します(管理者)",
                    "Модель/язык по умолчанию (админ)",
                ),
            "llm-channel-profile" to
                L(
                    "채널프로필",
                    "チャンネルプロフィール",
                    null,
                    "Set this channel's AI answer profile (admin)",
                    "このチャンネルのAI応答プロフィールを設定します(管理者)",
                    "Профиль ИИ канала (админ)",
                ),
            "ai-onboard" to
                L(
                    "ai자동설정",
                    "ai自動設定",
                    null,
                    "Auto-configure this channel's AI (admin)",
                    "このチャンネルのAIを自動設定します(管理者)",
                    "Авто-настройка ИИ канала (админ)",
                ),
            "ai-onboard-optout" to
                L(
                    "학습제외",
                    "学習除外",
                    null,
                    "Opt your messages out of server AI auto-learning",
                    "自分のメッセージをサーバーAIの自動学習から除外します",
                    "Исключить мои сообщения из авто-обучения ИИ",
                ),
            "ai-instruction" to
                L(
                    "ai지침",
                    "ai指示",
                    null,
                    "Add/edit this channel AI's freeform instruction (admin)",
                    "このチャンネルAIへの自由指示を追加/編集します(管理者)",
                    "Инструкция ИИ канала (админ)",
                ),
            "providers" to
                L("프로바이더목록", "プロバイダー一覧", null, "View the provider pool (admin)", "プロバイダープールを見ます(管理者)", "Список пула провайдеров (админ)"),
            "provider-approve" to
                L("프로바이더승인", "プロバイダー承認", null, "Approve a provider (admin)", "プロバイダーを承認します(管理者)", "Одобрить провайдера (админ)"),
            "provider-remove" to
                L("프로바이더제거", "プロバイダー削除", null, "Remove a provider (admin)", "プロバイダーを削除します(管理者)", "Удалить провайдера (админ)"),
            "llm-block" to L("사용자차단", "ユーザーブロック", null, "Block a user (admin)", "ユーザーをブロックします(管理者)", "Заблокировать пользователя (админ)"),
            "llm-unblock" to
                L("차단해제", "ブロック解除", null, "Unblock a user (admin)", "ユーザーのブロックを解除します(管理者)", "Разблокировать пользователя (админ)"),
            "menu" to
                L(
                    "메뉴",
                    "メニュー",
                    "меню",
                    "Open the start panel (ask/contribute/settings/help)",
                    "スタートパネルを開きます(質問・貢献・設定・ヘルプ)",
                    "Открыть стартовую панель",
                ),
            "llm-settings" to
                L("설정", "設定", "настройки", "Open the settings panel (admin)", "設定パネルを開きます(管理者)", "Открыть панель настроек (админ)"),
            "ai-network-map" to
                L("네트워크지도", "ネットワーク地図", null, "Show the AI network map (admin)", "AIネットワーク地図を見ます(管理者)", "Карта ИИ-сети (админ)"),
            "ai-network-check" to
                L(
                    "네트워크점검",
                    "ネットワーク点検",
                    null,
                    "Show the AI network launch checklist (admin)",
                    "AIネットワークの運用チェックリストを見ます(管理者)",
                    "Проверка ИИ-сети (админ)",
                ),
            "ai-knowledge-list" to
                L(
                    "지식목록",
                    "知識一覧",
                    null,
                    "List channel RAG knowledge spaces (admin)",
                    "チャンネルRAG知識空間の一覧を見ます(管理者)",
                    "Список знаний RAG (админ)",
                ),
            "ai-knowledge-add" to
                L(
                    "지식추가",
                    "知識追加",
                    null,
                    "Add a RAG knowledge source to this channel (admin)",
                    "このチャンネルのRAG知識ソースを追加します(管理者)",
                    "Добавить знание RAG (админ)",
                ),
            "ai-knowledge-search" to
                L(
                    "지식검색",
                    "知識検索",
                    null,
                    "Search this channel's RAG knowledge (admin)",
                    "このチャンネルのRAG知識を検索します(管理者)",
                    "Поиск знаний RAG (админ)",
                ),
            "ai-knowledge-index-plan" to
                L("지식색인계획", "索引計画", null, "Show the RAG indexing plan (admin)", "RAG索引計画を見ます(管理者)", "План индексации RAG (админ)"),
            "ai-knowledge-approve" to
                L(
                    "지식승인",
                    "知識承認",
                    null,
                    "Approve a review-risk RAG source (admin)",
                    "検討が必要なRAGソースを承認します(管理者)",
                    "Одобрить источник RAG (админ)",
                ),
            "ai-knowledge-delete" to
                L("지식삭제", "知識削除", null, "Delete a RAG knowledge source (admin)", "RAG知識ソースを削除します(管理者)", "Удалить источник RAG (админ)"),
            "ai-knowledge-jobs" to
                L("지식색인작업", "索引ジョブ", null, "List RAG indexing jobs (admin)", "RAG索引ジョブの一覧を見ます(管理者)", "Задачи индексации RAG (админ)"),
            "ai-knowledge-job-complete" to
                L(
                    "지식색인완료",
                    "索引完了",
                    null,
                    "Record a RAG indexing job result (admin)",
                    "RAG索引ジョブの結果を記録します(管理者)",
                    "Записать результат индексации RAG (админ)",
                ),
            "ai-preset-catalog" to L("프리셋목록", "プリセット一覧", null, "Browse shared AI presets", "共有AIプリセットを見ます", "Список пресетов ИИ"),
            "ai-preset-import" to
                L("프리셋가져오기", "プリセット取込", null, "Import a shared preset into this channel", "共有プリセットをこのチャンネルに取り込みます", "Импорт пресета"),
            "ai-preset-like" to L("프리셋좋아요", "プリセットいいね", null, "Like a shared AI preset", "共有AIプリセットにいいねします", "Лайк пресета"),
            "ai-preset-report" to
                L("프리셋신고", "プリセット通報", null, "Report an unsafe or inappropriate preset", "不適切な公開AIプリセットを通報します", "Пожаловаться на пресет"),
            "ai-preset-moderation" to
                L("프리셋검수", "プリセット審査", null, "Review preset reports (admin)", "プリセット通報・審査キューを見ます(管理者)", "Модерация пресетов (админ)"),
            "ai-preset-report-review" to
                L("프리셋신고처리", "通報処理", null, "Resolve a preset report (admin)", "プリセット通報を審査処理します(管理者)", "Решить жалобу на пресет (админ)"),
            "ai-multi-response-status" to
                L(
                    "다중응답상태",
                    "マルチ応答状態",
                    null,
                    "Show multi-response operations status (admin)",
                    "マルチ応答の運用状態を見ます(管理者)",
                    "Статус мульти-ответа (админ)",
                ),
            "ai-multi-response-set" to
                L(
                    "다중응답설정",
                    "マルチ応答設定",
                    null,
                    "Save multi-response policy (admin)",
                    "マルチ応答ポリシーを保存します(管理者)",
                    "Настройка мульти-ответа (админ)",
                ),
            "ai-multi-response-dry-run" to
                L(
                    "다중응답실험",
                    "マルチ応答試験",
                    null,
                    "Safely dry-run multi-response fan-out (admin)",
                    "マルチ応答のfan-outを安全に試験実行します(管理者)",
                    "Пробный мульти-ответ (админ)",
                ),
            "bot-permissions" to
                L(
                    "봇권한",
                    "ボット権限",
                    null,
                    "Check bot permissions and mention setup (admin)",
                    "ボット権限とメンション呼び出し設定を点検します(管理者)",
                    "Права бота (админ)",
                ),
            "ask-long" to L("긴질문", "長い質問", null, "Enter a long question via modal", "長い質問をモーダルで入力します", "Длинный вопрос (модальное окно)"),
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
