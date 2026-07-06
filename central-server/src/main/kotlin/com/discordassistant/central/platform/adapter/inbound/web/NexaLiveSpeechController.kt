package com.discordassistant.central.platform.adapter.inbound.web

import com.discordassistant.central.participation.domain.service.sim.SimActor
import com.discordassistant.central.participation.domain.service.sim.SimActorKind
import com.discordassistant.central.participation.domain.service.sim.SimChannelKind
import com.discordassistant.central.participation.domain.service.sim.SimEvent
import com.discordassistant.central.participation.domain.service.sim.SimEventType
import com.discordassistant.central.participation.domain.service.sim.SimScenario
import com.discordassistant.central.participation.domain.service.sim.SimScenarioException
import com.discordassistant.central.platform.discord.nexa.LiveRunResult
import com.discordassistant.central.platform.discord.nexa.NexaLiveSpeechService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 어드민 "NEXA 테스트 — 실제 발화 모드" API — participation 결정에 더해 **SPEAK 결정마다 실제 GLM(z.ai)** 으로
 * 진짜 문장을 생성한다(이전 turn 을 대화 맥락으로 + 유효 기억 주입). **실제 Discord 전송은 0**(생성까지만).
 *
 * **인증**: 경로(`/api/ai-network/nexa` 하위)는 [com.discordassistant.central.global.security
 * .AiNetworkApiSecurityFilter] 의 sensitive 접두사에 이미 등록돼 OAuth(허용목록)/admin-token 없이 차단된다(필터
 * 무수정·기존 가드 재사용). 웹 대시보드 전용·비관리자 차단.
 *
 * **shadow only**: [NexaLiveSpeechService] 가 전송 경로(actionruntime/JDA)를 주입받지 않아 호출 경로가
 * 존재하지 않고, 결과 `sends==0` 을 단언한다. 실제 GLM 호출은 SPEAK 결정에서만 일어난다(IGNORE/REACT 0).
 */
@RestController
@RequestMapping("/api/ai-network/nexa/sim")
class NexaLiveSpeechController(
    private val live: NexaLiveSpeechService,
) {
    /** 실제 발화 모드 사전 정의(멀티턴 데모) 시나리오 목록. */
    @GetMapping("/live-scenarios")
    fun scenarios(): List<LivePredefinedScenarioDto> =
        live.listPredefined().map { LivePredefinedScenarioDto(it.scenarioId, it.title, it.channelKind) }

    /** 사전 정의 멀티턴 데모를 실제 발화 모드로 실행(전송 0, SPEAK 시에만 GLM 호출). */
    @PostMapping("/live-scenarios/{scenarioId}/run")
    fun runPredefined(
        @PathVariable scenarioId: String,
    ): LiveRunResultDto = live.runPredefined(scenarioId).toDto()

    /** 직접 입력 멀티턴 시나리오를 실제 발화 모드로 실행(전송 0). 검증 실패는 400. */
    @PostMapping("/run-live")
    fun runCustom(
        @RequestBody request: LiveScenarioRequest,
    ): LiveRunResultDto = live.run(request.toDomain()).toDto()
}

/** 실제 발화 모드 직접 입력 시나리오 요청(합성·가명만). */
data class LiveScenarioRequest(
    val scenarioId: String,
    val title: String? = null,
    val channelKind: String = "MEMBER",
    val seed: Long = 0,
    val actors: List<LiveActorRequest> = emptyList(),
    val events: List<LiveEventRequest> = emptyList(),
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

data class LiveActorRequest(
    val actorId: String,
    val kind: String,
    val displayName: String? = null,
) {
    fun toDomain(): SimActor = SimActor(actorId, parseEnum(kind, SimActorKind.entries, "actor.kind"), displayName)
}

data class LiveEventRequest(
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

private fun LiveRunResult.toDto() =
    LiveRunResultDto(
        scenarioId = scenarioId,
        channelKind = channelKind,
        // 생성까지만 — 실제 전송 0 임을 응답에서 명시(SHADOW 배지 근거).
        shadow = shadow,
        sends = sends,
        decisions = decisions,
        speakDecisions = speakDecisions,
        glmCalls = glmCalls,
        turns =
            turns.map {
                LiveTurnDto(
                    seq = it.seq,
                    atMs = it.atMs,
                    triggerMessageId = it.triggerMessageId,
                    spoke = it.spoke,
                    outcome = it.outcome,
                    text = it.text,
                    error = it.error,
                    injectedTurns = it.injectedTurns,
                    injectedMemory = it.injectedMemory,
                )
            },
    )

/** 실제 발화 모드 사전 정의 시나리오 메타 DTO. */
data class LivePredefinedScenarioDto(
    val scenarioId: String,
    val title: String,
    val channelKind: String,
)

/** 실제 발화 모드 결과 DTO. [shadow]=true·[sends]=0(생성까지만)·[glmCalls]=SPEAK 시 실제 GLM 호출 수. */
data class LiveRunResultDto(
    val scenarioId: String,
    val channelKind: String,
    val shadow: Boolean,
    val sends: Int,
    val decisions: Int,
    val speakDecisions: Int,
    val glmCalls: Int,
    val turns: List<LiveTurnDto>,
)

/** 실제 발화 모드 한 turn DTO(실제 생성 문장 + 주입 컨텍스트/기억 요약). */
data class LiveTurnDto(
    val seq: Int,
    val atMs: Long,
    val triggerMessageId: String?,
    val spoke: Boolean,
    val outcome: String,
    val text: String?,
    val error: String?,
    val injectedTurns: Int,
    val injectedMemory: List<String>,
)
