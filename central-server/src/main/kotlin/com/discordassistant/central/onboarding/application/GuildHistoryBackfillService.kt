package com.discordassistant.central.onboarding.application

import com.discordassistant.central.discord.DiscordBot
import com.discordassistant.central.knowledge.application.KnowledgeSafety
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * 과거 메시지 백필(Phase 2b)의 **순수 정제 로직**. 채널 핀/공지(기본) + 화이트리스트 채널 본문(옵션)을 수집한
 * raw 메시지를 **RAG 색인용 텍스트**로 정제한다. "파인튜닝 학습"이 아니라 **RAG 색인**이라 row 삭제로 즉시 잊을 수 있다.
 *
 * 설계 원칙(ADR 0003/0004 — 동의·공정성·프라이버시):
 *  - 봇/웹훅/시스템 메시지 제외(자기참조 색인 루프 방지), opt-out 사용자 제외.
 *  - [KnowledgeSafety.containsSensitiveMaterial] 로 **민감 라인 마스킹/제외**.
 *  - [KnowledgeSafety.looksRiskyInstruction] 으로 **프롬프트 인젝션/탈옥/권한탈취 의도 라인 제외**
 *    (저장형 인젝션 1차 방어 — 백필은 비관리자 사용자 생성 콘텐츠라 색인 게이트가 backstop).
 *  - 작성자 **익명화**(user id SHA-256 해시) — 원본 user id 는 색인 텍스트에 노출하지 않는다.
 *  - 본문에 호명된 **제3자 멘션(user/role/channel) 마스킹** — `<@id>`/`<@&id>`/`<#id>` 토큰을
 *    `@사용자`/`@역할`/`#채널` 같은 익명 토큰으로 치환해 표시이름/실명이 색인에 남지 않게 한다.
 *
 * JDA I/O(핀/히스토리 조회·권한·인텐트 가드)는 [DiscordBot] 의 리스너(JDA 어댑터 글루)가 담당하고,
 * 이 클래스는 JDA 비의존 순수 로직만 보유해 단위테스트로 커버한다.
 */
@Service
class GuildHistoryBackfillService {
    /**
     * 정제 입력용 JDA 비의존 데이터 클래스. JDA Message 를 이 형태로 변환한 뒤 순수 로직으로 처리해 테스트 용이성을 확보한다.
     *
     * @param authorId 작성자 user id(익명화 전 원본 — 색인 텍스트에는 절대 노출하지 않는다).
     * @param isBot 봇 작성자 여부.
     * @param isWebhook 웹훅 작성자 여부.
     * @param isSystem 시스템 메시지(입장/핀 알림 등) 여부.
     * @param pinned 핀(공지) 메시지 여부.
     * @param content **raw** 본문(getContentRaw — 멘션이 `<@id>`/`<@&id>`/`<#id>` 토큰으로 남아 있어야 마스킹 가능).
     *  표시이름으로 해석된 contentDisplay 를 쓰면 제3자 실명이 색인되므로 raw 를 받아 [maskMentions] 로 치환한다.
     */
    data class RawMsg(
        val authorId: Long,
        val isBot: Boolean,
        val isWebhook: Boolean,
        val isSystem: Boolean,
        val pinned: Boolean,
        val content: String,
    )

    /**
     * 정제 결과(JDA 비의존). [indexText] 가 비어 있으면 색인할 게 없다는 뜻.
     *
     * @param scrubbedCount 제외된 라인 수(민감정보 라인 + 인젝션 의심 라인 합계). 값은 절대 노출하지 않는다.
     * @param riskyInstructionLineCount 그중 [KnowledgeSafety.looksRiskyInstruction] 에 걸려 제외된
     *  프롬프트 인젝션/탈옥/권한탈취 의심 라인 수(저장형 인젝션 가시성/감사용).
     */
    data class SanitizedBackfill(
        val collectedCount: Int,
        val scrubbedCount: Int,
        val indexText: String,
        val riskyInstructionLineCount: Int = 0,
    )

    /**
     * **순수 함수** — 수집한 raw 메시지를 RAG 색인용 텍스트로 정제한다(JDA 비의존, 단위테스트 대상).
     *
     *  1. 봇/웹훅/시스템 메시지 제외(자기참조 색인 루프 방지).
     *  2. opt-out 사용자 메시지 제외.
     *  3. 빈 본문 제외.
     *  4. [KnowledgeSafety.containsSensitiveMaterial] 매칭 라인(민감정보)·[KnowledgeSafety.looksRiskyInstruction]
     *     매칭 라인(프롬프트 인젝션/탈옥/권한탈취 의도)은 색인 텍스트에서 제거(스크럽 카운트 증가).
     *     모든 라인이 제거되면 메시지 자체를 제외.
     *  5. [maskMentions] 로 제3자 멘션(user/role/channel) 토큰을 익명 토큰으로 치환(표시이름/실명 비노출).
     *  6. 작성자 익명화 — 같은 user id 는 같은 `[익명N]` 라벨로, 원본 id 는 노출하지 않는다.
     *
     * @return [SanitizedBackfill] — 색인된 메시지 수/스크럽된 라인 수/색인용 텍스트.
     */
    fun sanitizeMessages(
        rawMessages: List<RawMsg>,
        optedOutUserIds: Set<Long> = emptySet(),
    ): SanitizedBackfill {
        var scrubbed = 0
        var riskyInstruction = 0
        val anonLabels = LinkedHashMap<String, String>()
        val lines = mutableListOf<String>()
        var collected = 0
        for (msg in rawMessages) {
            if (msg.isBot || msg.isWebhook || msg.isSystem) continue
            if (msg.authorId in optedOutUserIds) continue
            val content = msg.content.trim()
            if (content.isBlank()) continue

            val scrubResult = scrubLines(content)
            scrubbed += scrubResult.scrubbedCount
            riskyInstruction += scrubResult.riskyInstructionCount
            val cleanedLines = scrubResult.kept
            if (cleanedLines.isEmpty()) continue // 전부 민감/인젝션 라인이라 색인할 본문이 없음

            // 제3자 멘션은 라인 결합 후 한 번에 마스킹(표시이름/실명이 색인 텍스트에 남지 않게).
            val masked = maskMentions(cleanedLines.joinToString(" "))
            if (masked.isBlank()) continue

            val hash = hashAuthor(msg.authorId)
            val label = anonLabels.getOrPut(hash) { "익명${anonLabels.size + 1}" }
            collected++
            lines.add("[$label] $masked")
        }
        return SanitizedBackfill(
            collectedCount = collected,
            scrubbedCount = scrubbed,
            indexText = lines.joinToString("\n\n"),
            riskyInstructionLineCount = riskyInstruction,
        )
    }

    /**
     * 본문에 호명된 제3자 멘션 토큰을 익명 토큰으로 치환한다(순수 함수, 단위테스트 대상).
     *  - `<@123>` / `<@!123>` (유저, nickname 형 포함) → `@사용자`
     *  - `<@&123>` (역할) → `@역할`
     *  - `<#123>` (채널) → `#채널`
     *
     * raw 본문(getContentRaw)을 받는다는 전제다. 이렇게 하면 표시이름/실명이 색인에 절대 남지 않는다.
     * 혹시 contentDisplay 가 섞여 들어와도 raw 토큰만 있는 안전한 경우를 보장한다(없으면 no-op).
     */
    fun maskMentions(text: String): String =
        text
            .replace(USER_MENTION, "@사용자")
            .replace(ROLE_MENTION, "@역할")
            .replace(CHANNEL_MENTION, "#채널")
            .trim()

    /** user id 를 SHA-256 으로 익명화한다(원본 id 비노출). 같은 id → 같은 해시(라벨 안정성). */
    fun hashAuthor(userId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("author:$userId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** [scrubLines] 결과 — 살아남은 라인, 제거된 총 라인 수, 그중 인젝션 의심 라인 수(값은 절대 노출하지 않음). */
    private data class ScrubResult(
        val kept: List<String>,
        val scrubbedCount: Int,
        val riskyInstructionCount: Int,
    )

    /**
     * 라인 단위로 민감정보·프롬프트 인젝션 의도를 제거한다(저장형 인젝션 1차 방어).
     *  - [KnowledgeSafety.containsSensitiveMaterial] 매칭 라인 → 민감정보(시크릿) 노출 방지 위해 제거.
     *  - [KnowledgeSafety.looksRiskyInstruction] 매칭 라인 → "이전 지시 무시"·"토큰 수집" 류 명백한 인젝션
     *    문구가 RAG 로 색인돼 시스템 프롬프트에 영구 주입되는 것을 막기 위해 제거.
     * 둘 다 [scrubbedCount] 에 반영하고, 인젝션 라인은 [riskyInstructionCount] 에도 별도 집계한다(색인 게이트/감사용).
     */
    private fun scrubLines(content: String): ScrubResult {
        var scrubbed = 0
        var risky = 0
        val kept = mutableListOf<String>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (KnowledgeSafety.containsSensitiveMaterial(line)) {
                scrubbed++
                continue // 민감 라인은 색인 텍스트에서 통째로 제거(값 노출 금지).
            }
            if (KnowledgeSafety.looksRiskyInstruction(line)) {
                scrubbed++
                risky++
                continue // 인젝션 의심 라인은 색인 텍스트에서 통째로 제거(저장형 인젝션 방지).
            }
            kept.add(line)
        }
        return ScrubResult(kept = kept, scrubbedCount = scrubbed, riskyInstructionCount = risky)
    }

    companion object {
        /** JDA history.retrievePast 단일 호출 상한(레이트리밋·메모리 보호). DiscordBot 어댑터가 참조한다. */
        const val MAX_HISTORY_PER_CALL = 100

        // 역할 멘션(<@&id>)을 유저 멘션(<@id>)보다 먼저 치환해야 `&` 잔재가 안 남는다.
        private val ROLE_MENTION = Regex("""<@&\d+>""")
        private val USER_MENTION = Regex("""<@!?\d+>""")
        private val CHANNEL_MENTION = Regex("""<#\d+>""")
    }
}
