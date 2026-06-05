package com.discordassistant.central.discord

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ResponseMode
import com.discordassistant.central.domain.SupportedLanguage
import com.discordassistant.central.knowledge.domain.model.EmbeddingJobStatus
import com.discordassistant.central.multiresponse.domain.model.MultiResponseMode
import com.discordassistant.central.preset.domain.model.PresetReportStatus
import com.discordassistant.central.provider.domain.model.ProviderModelScope
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData

/**
 * 슬래시/컨텍스트 명령 **정의 카탈로그**. DiscordBot(god class)에서 명령 정의 블록을 분리.
 * 선택지(choice)는 도메인 enum(ResponseMode·ModelBurden·PresetReportStatus·EmbeddingJobStatus·
 * ProviderModelScope·MultiResponseMode)의 `*Choices()` SSOT 만 사용한다 — 라벨/값을 재하드코딩하지 않는다.
 */
object SlashCommandCatalog {
    /** (라벨, 와이어값) 쌍 목록을 옵션 choice 로 그대로 부착(도메인 enum SSOT 소비용). */
    private fun OptionData.choicePairs(pairs: List<Pair<String, String>>): OptionData {
        pairs.forEach { (label, value) -> addChoice(label, value) }
        return this
    }

    fun all(): List<CommandData> {
        val adminPerm = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)
        return listOf<net.dv8tion.jda.api.interactions.commands.build.CommandData>(
            Commands
                .slash("ask", "커뮤니티 로컬 AI 에게 질문합니다")
                .addOption(OptionType.STRING, "prompt", "질문 내용", true)
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "model", "원하는 모델(비우면 채널/서버 기본값)", false)
                        .setAutoComplete(true),
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "mode", "응답 속도/품질 모드", false)
                        .choicePairs(ResponseMode.slashChoices()),
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.BOOLEAN, "web", "웹 검색으로 최신 정보를 찾아 답변(출처 표시)", false),
                ),
            Commands
                .slash("imagine", "커뮤니티 로컬 Stable Diffusion 으로 이미지를 생성합니다")
                .addOption(OptionType.STRING, "prompt", "만들고 싶은 이미지 설명", true),
            Commands.slash("models", "사용 가능한 모델 수준을 확인합니다"),
            Commands.slash("catalog", "이 서버에서 제공 중인 모델 목록을 봅니다"),
            Commands.slash("my-usage", "내 오늘 사용량을 확인합니다"),
            Commands.slash("contributions", "커뮤니티 기여 리더보드를 봅니다"),
            Commands.slash("community-stats", "익명 커뮤니티 기여 통계를 봅니다"),
            Commands.slash("level", "이 서버 냥시스턴트의 활동 레벨/경험치를 봅니다"),
            Commands.slash("fairness", "공정성 리포트를 봅니다(관리자)").setDefaultPermissions(adminPerm),
            Commands.slash("privacy", "이 서버의 AI 처리/프라이버시 안내"),
            Commands.slash("help", "명령 종합 도움말을 봅니다"),
            Commands.slash("welcome", "서버 환영/안내 메시지를 봅니다"),
            Commands
                .slash("llm-welcome-set", "서버 환영/안내 메시지를 설정합니다(관리자)")
                .addOption(OptionType.STRING, "message", "환영 메시지", true)
                .setDefaultPermissions(adminPerm),
            Commands.slash("provider-join", "프로바이더로 참여합니다"),
            Commands.slash("provider-pause", "요청 수신을 일시정지합니다"),
            Commands.slash("provider-resume", "요청 수신을 재개합니다"),
            Commands.slash("provider-leave", "풀에서 나갑니다"),
            Commands.slash("provider-status", "내 프로바이더 상태를 확인합니다"),
            Commands
                .slash("provider-models", "제공 모델 수동 지정(보통 불필요 — 에이전트가 자동 감지)")
                .addOption(OptionType.STRING, "models", "내 PC 의 Ollama 모델명(쉼표 구분). 비워두면 자동 감지", true),
            Commands
                .slash("provider-limit", "모델별 한도를 설정합니다")
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "model", "대상 모델", true)
                        .setAutoComplete(true),
                ).addOption(OptionType.INTEGER, "daily", "하루 한도(0=무제한)", true)
                .addOption(OptionType.INTEGER, "concurrency", "동시 처리 수", true)
                .addOption(OptionType.INTEGER, "seconds", "요청당 최대 초", true),
            Commands
                .slash("provider-scope", "모델 허용 범위를 설정합니다")
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "model", "대상 모델", true)
                        .setAutoComplete(true),
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "role", "허용 범위", true)
                        .choicePairs(ProviderModelScope.slashChoices()),
                ),
            Commands
                .slash("provider-schedule", "가용 시간대를 설정합니다(UTC 시, 시간 밖 자동정지)")
                .addOption(OptionType.INTEGER, "from", "시작 시(0~23, UTC)", true)
                .addOption(OptionType.INTEGER, "to", "종료 시(0~23, UTC; from==to 면 24시간)", true),
            Commands
                .slash("llm-allow-channel", "LLM 사용 채널을 허용합니다(관리자)")
                .addOption(OptionType.CHANNEL, "channel", "허용 채널", true)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("llm-deny-channel", "LLM 사용 채널을 금지합니다(관리자)")
                .addOption(OptionType.CHANNEL, "channel", "금지 채널", true)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("llm-role-policy", "역할별 허용 수준을 설정합니다(관리자)")
                .addOption(OptionType.ROLE, "role", "대상 역할", true)
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "level", "허용 모델 수준", true)
                        .choicePairs(ModelBurden.rolePolicyChoices()),
                ).addOption(OptionType.INTEGER, "limit", "하루 한도", true)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("llm-guild-defaults", "길드 기본 모델/언어를 설정합니다(관리자)")
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "model", "기본 모델(비우면 자동 선택)", false)
                        .setAutoComplete(true),
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "language", "언어", false)
                        .choicePairs(SupportedLanguage.choices()),
                ).setDefaultPermissions(adminPerm),
            Commands
                .slash("llm-channel-profile", "이 채널의 AI 프로필 설정 패널을 엽니다(관리자)")
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-onboard", "이 채널 AI를 자동으로 설정합니다(관리자)")
                .addOption(OptionType.CHANNEL, "backfill-channel", "본문까지 학습할 채널(생략 시 핀/공지만)", false)
                .addOption(OptionType.INTEGER, "history-limit", "수집할 최근 메시지 수(최대 100)", false)
                .setDefaultPermissions(adminPerm),
            // 누구나 본인에 한해 사용(관리자 권한 불필요) — 본인 메시지를 자동 온보딩 백필 RAG 색인에서 제외.
            Commands
                .slash("ai-onboard-optout", "내 메시지를 서버 AI 자동 학습(백필 색인)에서 제외/해제합니다")
                .addOption(OptionType.BOOLEAN, "enable", "true=제외 등록, false=해제(생략 시 토글)", false),
            Commands
                .slash("ai-instruction", "이 채널 AI에 자유 지침을 추가/수정합니다(관리자)")
                .addOption(OptionType.STRING, "text", "AI에게 줄 자연어 지침(비우면 현재 지침 확인)", false)
                .setDefaultPermissions(adminPerm),
            Commands.slash("providers", "프로바이더 풀 상태를 봅니다(관리자)").setDefaultPermissions(adminPerm),
            Commands
                .slash("provider-approve", "프로바이더 등록을 승인합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("provider-remove", "프로바이더를 제거합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("llm-block", "사용자를 차단합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("llm-unblock", "사용자 차단을 해제합니다(관리자)")
                .addOption(OptionType.USER, "user", "대상 유저", true)
                .setDefaultPermissions(adminPerm),
            // 인터랙티브(차수 13): 설정 패널(버튼/Select #147/180), 모달 입력(#189)
            Commands.slash("menu", "시작 패널을 엽니다(질문·기여·설정·도움말 한 곳에서)"),
            Commands.slash("llm-settings", "설정 패널을 엽니다(관리자)").setDefaultPermissions(adminPerm),
            Commands.slash("ai-network-map", "AI 네트워크 지도와 채널 AI 구성을 봅니다(관리자)").setDefaultPermissions(adminPerm),
            Commands.slash("ai-network-check", "AI 네트워크 출시/운영 체크리스트를 봅니다(관리자)").setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-list", "채널 RAG 지식공간과 소스 상태를 봅니다(관리자)")
                .addOption(OptionType.STRING, "space-id", "상세 조회할 지식공간 ID", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-add", "현재 채널 RAG 지식공간에 링크/텍스트 소스를 추가합니다(관리자)")
                .addOption(OptionType.STRING, "title", "지식 제목", true)
                .addOption(OptionType.STRING, "url", "https 링크", false)
                .addOption(OptionType.STRING, "text", "짧은 텍스트/FAQ 미리보기", false)
                .addOption(OptionType.STRING, "source-type", "link/text/faq/file/constitution/preset", false)
                .addOption(OptionType.STRING, "space-id", "기존 지식공간 ID", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-search", "현재 채널 또는 특정 지식공간에서 RAG 지식을 검색합니다(관리자)")
                .addOption(OptionType.STRING, "query", "검색어", true)
                .addOption(OptionType.STRING, "space-id", "검색할 지식공간 ID", false)
                .addOption(OptionType.INTEGER, "limit", "결과 수(1~20)", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-index-plan", "RAG 색인 계획과 실행 명령을 봅니다(관리자)")
                .addOption(OptionType.STRING, "space-id", "지식공간 ID", false)
                .addOption(OptionType.BOOLEAN, "force", "이미 색인된 소스도 재색인 계획에 포함", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-jobs", "RAG 색인 작업 큐와 최근 결과를 봅니다(관리자)")
                .addOption(OptionType.STRING, "space-id", "지식공간 ID", false)
                .addOption(OptionType.INTEGER, "limit", "결과 수(1~20)", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-job-complete", "RAG 색인 작업 완료/실패 상태를 기록합니다(관리자)")
                .addOption(OptionType.STRING, "job-id", "색인 작업 ID", true)
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "status", "completed/failed/cancelled", false)
                        .choicePairs(EmbeddingJobStatus.completionChoices()),
                ).addOption(OptionType.STRING, "reason", "실패/취소 사유", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-approve", "검토 필요한 RAG 지식 소스를 색인 대기로 승인합니다(관리자)")
                .addOption(OptionType.STRING, "space-id", "지식공간 ID", true)
                .addOption(OptionType.STRING, "source-id", "지식 소스 ID", true)
                .addOption(OptionType.STRING, "reason", "승인 사유", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-knowledge-delete", "RAG 지식 소스를 삭제합니다(관리자)")
                .addOption(OptionType.STRING, "space-id", "지식공간 ID", true)
                .addOption(OptionType.STRING, "source-id", "지식 소스 ID", true)
                .addOption(OptionType.STRING, "reason", "삭제 사유", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-preset-catalog", "공개 AI 프리셋 공유 목록을 봅니다(관리자)")
                .addOption(OptionType.STRING, "query", "검색어", false)
                .addOption(OptionType.STRING, "category", "카테고리", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-preset-import", "공개 프리셋을 현재 채널 AI에 가져옵니다(관리자)")
                .addOption(OptionType.STRING, "published-id", "가져올 공개 프리셋 ID", true)
                .addOption(OptionType.BOOLEAN, "confirm-conflicts", "기존 채널 AI/정책 덮어쓰기 확인", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-preset-like", "공개 AI 프리셋에 좋아요를 누릅니다")
                .addOption(OptionType.STRING, "published-id", "좋아요할 공개 프리셋 ID", true),
            Commands
                .slash("ai-preset-report", "부적절한 공개 AI 프리셋을 신고합니다")
                .addOption(OptionType.STRING, "published-id", "신고할 공개 프리셋 ID", true)
                .addOption(OptionType.STRING, "reason", "신고 사유", true),
            Commands
                .slash("ai-preset-moderation", "프리셋 신고/검수 큐를 봅니다(관리자)")
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-preset-report-review", "프리셋 신고를 검수 처리합니다(관리자)")
                .addOption(OptionType.STRING, "report-id", "처리할 신고 ID", true)
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "decision", "dismiss/suspend/remove", true)
                        .choicePairs(PresetReportStatus.decisionChoices()),
                ).setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-multi-response-status", "다중응답 정책/부하/위험 상태를 봅니다(관리자)")
                .addOption(OptionType.CHANNEL, "channel", "확인할 채널(비우면 현재 채널)", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-multi-response-set", "현재 또는 선택 채널의 다중응답 정책을 저장합니다(관리자)")
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "mode", "single/compare/debate", true)
                        .choicePairs(MultiResponseMode.slashChoices()),
                ).addOption(OptionType.INTEGER, "candidates", "후보 수(1~3)", true)
                .addOption(OptionType.BOOLEAN, "synthesis", "후보 답변 합성 사용", false)
                .addOption(OptionType.BOOLEAN, "distinct-models", "서로 다른 모델 우선", false)
                .addOption(OptionType.INTEGER, "timeout", "타임아웃 초(10~300)", false)
                .addOption(OptionType.CHANNEL, "channel", "설정할 채널(비우면 현재 채널)", false)
                .setDefaultPermissions(adminPerm),
            Commands
                .slash("ai-multi-response-dry-run", "다중응답 fan-out 후보와 RAG 컨텍스트를 안전하게 드라이런합니다(관리자)")
                .addOption(OptionType.STRING, "prompt", "테스트 질문/프롬프트", true)
                .addOptions(
                    net.dv8tion.jda.api.interactions.commands.build
                        .OptionData(OptionType.STRING, "mode", "응답 속도/품질 모드", false)
                        .choicePairs(ResponseMode.slashChoices()),
                ).addOption(OptionType.CHANNEL, "channel", "드라이런할 채널(비우면 현재 채널)", false)
                .setDefaultPermissions(adminPerm),
            Commands.slash("bot-permissions", "봇 권한과 @멘션 호출 설정을 점검합니다(관리자)").setDefaultPermissions(adminPerm),
            Commands.slash("ask-long", "긴 질문을 모달 창으로 입력합니다"),
            // 컨텍스트 메뉴(#181): 메시지 우클릭 → 그 내용으로 질문
            Commands.message("AI에게 질문"),
        )
    }
}
