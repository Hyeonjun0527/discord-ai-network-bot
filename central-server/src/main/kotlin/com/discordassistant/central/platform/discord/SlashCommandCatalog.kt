package com.discordassistant.central.platform.discord

import com.discordassistant.central.shared.ResponseMode
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData

/**
 * 슬래시/컨텍스트 명령 **정의 카탈로그**.
 *
 * 의도적으로 **유저가 자주 쓰는 단순 명령만** 슬래시로 노출한다(≤10). 서버 관리·운영(채널 AI·
 * 지식/RAG·프리셋·다중응답·역할/채널 정책·프로바이더 승인 등 "화면이 필요한 관리")은 **웹 대시보드**
 * (`/설정` → publicBaseUrl/admin/dashboard)에서, 내 로컬 프로바이더 운영(일시정지·재개·한도·시간대)은
 * **데스크톱 앱**에서 한다. 슬래시 명령 수십 개로 채널 자동완성을 어지럽히지 않기 위함.
 *
 * 선택지(choice)는 도메인 enum(ResponseMode)의 `*Choices()` SSOT 만 사용한다 — 라벨/값 재하드코딩 금지.
 */
object SlashCommandCatalog {
    /** (라벨, 와이어값) 쌍 목록을 옵션 choice 로 그대로 부착(도메인 enum SSOT 소비용). */
    private fun OptionData.choicePairs(pairs: List<Pair<String, String>>): OptionData {
        pairs.forEach { (label, value) -> addChoice(label, value) }
        return this
    }

    fun all(): List<CommandData> =
        listOf(
            // ── 핵심: 질문/이미지 ───────────────────────────────────────────
            Commands
                .slash("ask", "AI에게 질문합니다 (무료 클라우드 기본 · 커뮤니티 로컬 연결 시 로컬)")
                .addOption(OptionType.STRING, "prompt", "질문 내용", true)
                .addOptions(
                    OptionData(OptionType.STRING, "model", "원하는 모델(비우면 채널/서버 기본값)", false)
                        .setAutoComplete(true),
                    OptionData(OptionType.STRING, "mode", "응답 속도/품질 모드", false)
                        .choicePairs(ResponseMode.slashChoices()),
                    OptionData(OptionType.BOOLEAN, "web", "웹 검색으로 최신 정보를 찾아 답변(출처 표시)", false),
                    // thinking 강제(on/off) — 프로젝트 운영자(admin-user-ids)만 적용, 그 외엔 무시(자동 라우팅).
                    OptionData(OptionType.STRING, "thinking", "추론 모드 강제(운영자 전용·그 외 무시)", false)
                        .addChoice("on", "on")
                        .addChoice("off", "off"),
                ),
            Commands
                .slash("imagine", "이미지를 생성합니다 (무료 클라우드 Stable Diffusion 기본 · 로컬 연결 시 로컬)")
                .addOption(OptionType.STRING, "prompt", "만들고 싶은 이미지 설명", true)
                .addOption(OptionType.BOOLEAN, "confirm", "결과를 채널에 올리기 전 본인 확인 단계(끄면 바로 게시)", false),
            // ── 시작/도움/설정 ──────────────────────────────────────────────
            Commands.slash("menu", "시작 패널을 엽니다(질문·기여·설정·도움말 한 곳에서)"),
            Commands.slash("help", "명령 종합 도움말을 봅니다"),
            // 관리 기능은 전부 웹 대시보드로. /설정 이 그 입구(공개) — 누구나 열어 링크 버튼으로 이동.
            Commands.slash("settings", "설정·관리 대시보드(웹)로 가는 링크를 봅니다"),
            // ── 내 정보(읽기 전용) ─────────────────────────────────────────
            Commands.slash("my-usage", "내 오늘 사용량을 확인합니다"),
            Commands.slash("nia", "니아와의 호감도를 봅니다"),
            Commands.slash("privacy", "이 서버의 AI 처리/프라이버시 안내"),
            // ── 프로바이더 참여(이후 운영은 데스크톱 앱) ────────────────────
            Commands.slash("provider-join", "프로바이더로 참여합니다(이후 운영은 데스크톱 앱)"),
            Commands.slash("provider-status", "내 프로바이더 상태를 확인합니다"),
            // ── 컨텍스트 메뉴(#181): 메시지 우클릭 → 그 내용으로 질문 ────────
            Commands.message("AI에게 질문"),
        )
}
