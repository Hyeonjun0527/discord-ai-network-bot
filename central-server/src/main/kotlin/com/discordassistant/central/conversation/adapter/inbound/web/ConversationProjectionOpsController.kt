package com.discordassistant.central.conversation.adapter.inbound.web

import com.discordassistant.central.conversation.application.port.out.DeadLetterRecord
import com.discordassistant.central.conversation.application.port.out.ProjectionLedgerPort
import com.discordassistant.central.conversation.application.replay.ReplayCriteria
import com.discordassistant.central.conversation.application.replay.ReplayProjectionService
import com.discordassistant.central.conversation.application.replay.ReplayReport
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * conversation projection **운영 내부 도구**(NEXA-P03-T018/T019). dead-letter 조사·재처리와 내부 replay 를
 * 노출한다.
 *
 * **내부 전용·dev 게이트(acceptance T018/T019)**: `central.dev.enabled=true`(기본 false)일 때만 빈으로 등록된다.
 * **운영에서 절대 켜지 말 것** — replay/재처리는 운영 도구이며 외부에 노출되지 않는다(DevController 와 같은 게이트).
 *
 * **원문 미노출(acceptance T018)**: dead-letter 조사 응답은 [DeadLetterView](event ID·분류 코드·시각)만 담는다 —
 * 원문/PII 가 로그·API 로 새지 않는다(logging-boundary.md). 운영자는 event ID 로만 조사한다.
 *
 * **replay side-effect-free(acceptance T019)**: replay 는 [ReplayProjectionService] 만 호출하며, 그 서비스는
 * Discord 전송 포트를 타입 수준에서 참조하지 않는다 — 이 컨트롤러를 통해서도 운영 Discord 전송이 일어날 수 없다.
 */
@RestController
@RequestMapping("/dev/conversation/projection")
@ConditionalOnProperty(prefix = "central.dev", name = ["enabled"], havingValue = "true")
class ConversationProjectionOpsController(
    private val ledger: ProjectionLedgerPort,
    private val replayService: ReplayProjectionService,
) {
    /** dead-letter 격리 목록(원문 미포함, 최신순). 운영자가 event ID 로 원인 조사. */
    @GetMapping("/dead-letters")
    fun deadLetters(
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DeadLetterView> = ledger.deadLetters(limit).map { it.toView() }

    /**
     * dead-letter 에서 event ID 를 격리 해제한다(운영 재처리 준비). 해제 후 재적용은 replay 로 한다(새 version)
     * — 격리 해제 자체는 부수효과를 만들지 않는다(외부 전송 없음).
     */
    @PostMapping("/dead-letters/clear")
    fun clearDeadLetter(
        @RequestBody req: ClearReq,
    ): Map<String, Boolean> {
        require(req.eventId.isNotBlank()) { "eventId 는 비어 있을 수 없습니다" }
        return mapOf("cleared" to ledger.clearDeadLetter(EventId(req.eventId)))
    }

    /**
     * 내부 replay 를 실행한다 — guild/channel/time range 를 새 projection version 으로 재생한다. Discord 전송
     * side effect 없음(서비스 계약). 재생 건수 요약을 돌려준다.
     */
    @PostMapping("/replay")
    fun replay(
        @RequestBody req: ReplayReq,
    ): ReplayReport {
        require(req.guildId > 0) { "guildId 는 양수여야 합니다: ${req.guildId}" }
        require(req.projectionVersion >= 0) { "projectionVersion 은 음수일 수 없습니다: ${req.projectionVersion}" }
        val criteria =
            ReplayCriteria(
                guildId = GuildId(req.guildId),
                channelId = req.channelId?.let { ChannelId(it) },
                from = Instant.parse(req.from),
                to = Instant.parse(req.to),
                projectionVersion = req.projectionVersion,
            )
        return replayService.replay(criteria)
    }

    private fun DeadLetterRecord.toView(): DeadLetterView =
        DeadLetterView(
            eventId = eventId.value,
            projectionVersion = projectionVersion,
            reasonCode = reasonCode,
            detail = detail,
            failedAtEpochMs = failedAtEpochMs,
        )

    /** dead-letter 조사 응답(원문·PII 미포함; event ID 로만 조사). */
    data class DeadLetterView(
        val eventId: String,
        val projectionVersion: Int,
        val reasonCode: String,
        val detail: String,
        val failedAtEpochMs: Long,
    )

    data class ClearReq(
        val eventId: String,
    )

    data class ReplayReq(
        val guildId: Long,
        val channelId: Long? = null,
        /** ISO-8601 시각(예: 2026-06-21T00:00:00Z). [from, to) 반열림 범위. */
        val from: String,
        val to: String,
        val projectionVersion: Int,
    )
}
