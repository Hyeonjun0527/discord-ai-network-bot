package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.application.sim.NexaSimulationService
import com.discordassistant.central.participation.application.sim.PredefinedScenario
import com.discordassistant.central.participation.domain.service.sim.SimActor
import com.discordassistant.central.participation.domain.service.sim.SimActorKind
import com.discordassistant.central.participation.domain.service.sim.SimChannelKind
import com.discordassistant.central.participation.domain.service.sim.SimDecision
import com.discordassistant.central.participation.domain.service.sim.SimEvent
import com.discordassistant.central.participation.domain.service.sim.SimEventType
import com.discordassistant.central.participation.domain.service.sim.SimResult
import com.discordassistant.central.participation.domain.service.sim.SimScenario
import com.discordassistant.central.participation.domain.service.sim.SimScenarioException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 어드민 "NEXA 테스트" 시뮬레이션 API — NEXA 의 participation 행동을 **실제 Discord 전송 없이(shadow)** 재생한다.
 *
 * **인증**: 경로(`/api/ai-network/nexa` 하위)는 [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter]
 * 의 sensitive read 접두사(`/api/ai-network/nexa`)에 이미 등록돼 있어 GET 은 OAuth(허용목록)/admin-token 없이 403,
 * POST 는 unsafe method 라 똑같이 admin 가드를 탄다(필터 무수정 — 기존 가드 재사용). 웹 대시보드 전용·비관리자 차단.
 *
 * **shadow only**: [NexaSimulationService] 가 순수 시뮬레이터를 호출하고 결과의 `sends==0` 을 단언한다 —
 * 어떤 입력에도 실제 전송·실제 GLM 호출은 0 이다. 응답은 가명 시나리오에 대한 결정 궤적만 담는다(원문/개별
 * 사용자 행동 비포함 — 입력 자체가 합성).
 */
@RestController
@RequestMapping("/api/ai-network/nexa/sim")
class NexaSimulationController(
    private val simulation: NexaSimulationService,
) {
    /** 사전 정의 시나리오 목록(드롭다운/카드). */
    @GetMapping("/scenarios")
    fun scenarios(): List<PredefinedScenarioDto> = simulation.listPredefined().map { it.toDto() }

    /** 사전 정의 시나리오 실행(전송 0). */
    @PostMapping("/scenarios/{scenarioId}/run")
    fun runPredefined(
        @PathVariable scenarioId: String,
    ): SimResultDto = simulation.runPredefined(scenarioId).toDto()

    /** 직접 입력 시나리오 실행(전송 0). 검증 실패는 400(SimScenarioException). */
    @PostMapping("/run")
    fun runCustom(
        @RequestBody request: SimScenarioRequest,
    ): SimResultDto = simulation.run(request.toDomain()).toDto()
}

/** 직접 입력 시나리오 요청 DTO(합성·가명만). */
data class SimScenarioRequest(
    val scenarioId: String,
    val title: String? = null,
    val channelKind: String = "MEMBER",
    val seed: Long = 0,
    val actors: List<SimActorRequest> = emptyList(),
    val events: List<SimEventRequest> = emptyList(),
) {
    fun toDomain(): SimScenario =
        SimScenario(
            scenarioId = scenarioId,
            title = title ?: scenarioId,
            channelKind = parseEnum(channelKind, SimChannelKind.entries, "channelKind"),
            seed = seed,
            actors = actors.map { it.toDomain() },
            events = events.map { it.toDomain() },
        )
}

data class SimActorRequest(
    val actorId: String,
    val kind: String,
    val displayName: String? = null,
) {
    fun toDomain(): SimActor = SimActor(actorId, parseEnum(kind, SimActorKind.entries, "actor.kind"), displayName)
}

data class SimEventRequest(
    val seq: Int,
    val atOffsetMs: Long,
    val type: String,
    val messageId: String? = null,
    val authorId: String? = null,
    val content: String? = null,
    val mentionsNexa: Boolean = false,
    val threadId: String? = null,
    val fault: String? = null,
) {
    fun toDomain(): SimEvent =
        SimEvent(
            seq = seq,
            atOffsetMs = atOffsetMs,
            type = parseEnum(type, SimEventType.entries, "event.type"),
            messageId = messageId,
            authorId = authorId,
            content = content,
            mentionsNexa = mentionsNexa,
            threadId = threadId,
            fault = fault,
        )
}

private fun <E : Enum<E>> parseEnum(
    raw: String,
    values: List<E>,
    field: String,
): E =
    values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        ?: throw SimScenarioException("$field 값이 올바르지 않습니다: $raw")

private fun PredefinedScenario.toDto() = PredefinedScenarioDto(scenarioId, title, channelKind)

private fun SimResult.toDto() =
    SimResultDto(
        scenarioId = scenarioId,
        channelKind = channelKind.name,
        // 실제 전송 0 회임을 응답에서 명시(페이지 SHADOW 배지 근거).
        shadow = shadow,
        sends = sends,
        energyLevel = energyLevel,
        faults = faults,
        summary =
            SimSummaryDto(
                decisions = decisions.size,
                speak = speakCount,
                react = reactCount,
                cancel = cancelCount,
            ),
        decisions = decisions.map { it.toDto() },
    )

private fun SimDecision.toDto() =
    SimDecisionDto(
        seq = seq,
        atMs = atMs,
        triggerMessageId = triggerMessageId,
        action = action.wireName,
        delayBucket = delayBucket.name,
        targetMessageId = targetMessageId,
        reason = reason,
        consumesGenerationQuota = consumesGenerationQuota,
    )

/** 사전 정의 시나리오 메타 DTO. */
data class PredefinedScenarioDto(
    val scenarioId: String,
    val title: String,
    val channelKind: String,
)

/** 시뮬레이션 결과 DTO. [shadow]=true·[sends]=0 으로 실제 전송 없음을 명시한다. */
data class SimResultDto(
    val scenarioId: String,
    val channelKind: String,
    val shadow: Boolean,
    val sends: Int,
    val energyLevel: Double,
    val faults: List<String>,
    val summary: SimSummaryDto,
    val decisions: List<SimDecisionDto>,
)

data class SimSummaryDto(
    val decisions: Int,
    val speak: Int,
    val react: Int,
    val cancel: Int,
)

/** 이벤트별 NEXA 결정 DTO(무엇을·왜·언제). */
data class SimDecisionDto(
    val seq: Int,
    val atMs: Long,
    val triggerMessageId: String?,
    /** 행동 안정 코드(ignore/wait/react/speak/cancel_pending). */
    val action: String,
    val delayBucket: String,
    val targetMessageId: String?,
    val reason: String,
    val consumesGenerationQuota: Boolean,
)
