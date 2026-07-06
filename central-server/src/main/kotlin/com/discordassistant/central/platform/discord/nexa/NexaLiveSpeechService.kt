package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.service.sim.NexaSimulator
import com.discordassistant.central.participation.domain.service.sim.SimActorKind
import com.discordassistant.central.participation.domain.service.sim.SimEventType
import com.discordassistant.central.participation.domain.service.sim.SimScenario
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.context.ConversationContextSelector
import com.discordassistant.central.speech.application.generation.GenerationBudget
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.RawThreadTurn
import com.discordassistant.central.speech.application.port.out.SceneContextReadPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.springframework.stereotype.Service

/**
 * 어드민 "NEXA 테스트 — 실제 발화 모드" application 서비스(어드민 대시보드 전용, platform 어댑터 seam).
 *
 * shadow 시뮬레이터([NexaSimulator])가 만든 participation 결정을 그대로 재생하되, **SPEAK 결정에 한해**
 * 실제 발화 파이프라인([NexaSpeechPipelineService])을 호출해 **실제 GLM(z.ai)** 으로 진짜 문장을 생성한다.
 * 이전 turn 들은 [ConversationContextSelector] 로 **대화 맥락**으로 주입되고, 시나리오가 명시한 유효 기억은
 * [MemoryRef] 로 함께 주입된다 — "NEXA 가 앞 대화를 기억하고 사람처럼 답하는지" 를 눈으로 본다.
 *
 * **Discord 전송 0(생성까지만, shadow)**:
 *  1. 이 서비스는 actionruntime/JDA 전송 경로([ParticipationActionRouter] 등)를 **주입받지 않는다** — 호출 경로가
 *     존재하지 않는다. 파이프라인이 돌려주는 "전송 직전" 결과([com.discordassistant.central.speech.application
 *     .PipelineResult])까지만 다루고 실제 send 는 하지 않는다.
 *  2. 결과 [LiveRunResult.sends] 는 항상 0(구조적 불변식). 실행 후 [requireNoSend] 로 한 번 더 단언한다.
 *
 * **실제 GLM 호출은 SPEAK 일 때만**: 각 이벤트의 participation 결정이 SPEAK 가 아니면(IGNORE/WAIT/REACT/취소)
 * 파이프라인을 **호출하지 않는다** — [SpeechTrigger.SPEAK] 로만 generation 게이트가 열린다(비용·자연스러움).
 *
 * **ADR 0006 경계**: 실제 GLM 호출은 [NexaSpeechPipelineService] → [com.discordassistant.central.speech.application
 * .generation.SpeechGenerationGate] → [com.discordassistant.central.speech.application.port.out.SpeechGenerationPort]
 * (= routing CloudLlm 포트 구현) 으로만 일어난다 — provider-agent glm 직접 호출·RoutingCloudSpeechGenerationAdapter
 * 직접 참조 없음(NexaArchitectureTest 준수). ZAI 키 없거나 GLM 실패면 포트가 빈 결과를 돌려주고 fallback 이
 * 침묵으로 안전 하강한다(전송 0·에러 표시).
 */
@Service
class NexaLiveSpeechService(
    private val consentGate: ConsentGate,
    private val pipeline: NexaSpeechPipelineService,
) {
    /** 사전 정의 멀티턴 데모 시나리오 목록(드롭다운/카드 메타). */
    fun listPredefined(): List<LivePredefinedScenario> =
        PREDEFINED.values.map { LivePredefinedScenario(it.scenarioId, it.title, it.channelKind.name) }

    /** 사전 정의 멀티턴 데모 시나리오를 실제 발화 모드로 재생한다. 알 수 없는 id 면 [IllegalArgumentException](→400). */
    fun runPredefined(scenarioId: String): LiveRunResult {
        val scenario = PREDEFINED[scenarioId] ?: throw IllegalArgumentException("알 수 없는 시나리오: $scenarioId")
        return run(scenario)
    }

    /**
     * 시나리오를 실제 발화 모드로 재생한다. participation 결정은 shadow 시뮬레이터로 만들고, SPEAK 결정마다 이전
     * turn 들을 대화 맥락으로 주입해 실제 GLM 으로 문장을 생성한다(전송 0).
     */
    fun run(scenario: SimScenario): LiveRunResult {
        // 1) participation 결정(shadow 시뮬레이터 — 휴리스틱 동일). 전송·GLM 호출 0.
        val simResult = NexaSimulator(scenario).run()

        // 2) 시나리오의 message 이벤트를 시간순으로 누적해 "그 시점까지의 대화" 로그를 구성한다(멀티턴 컨텍스트 원천).
        val messageEvents =
            scenario.events
                .filter { it.type == SimEventType.MESSAGE_CREATE && it.messageId != null }
        val labelOf = actorLabels(scenario)
        val nexaActorIds =
            scenario.actors
                .filter { it.kind == SimActorKind.NEXA }
                .map { it.actorId }
                .toSet()

        // 3) SPEAK 결정마다 그 trigger 메시지 직전까지의 turn 을 컨텍스트로 주입해 실제 발화를 생성한다.
        val turns = mutableListOf<LiveTurn>()
        for (decision in simResult.decisions) {
            if (decision.action != SocialActionKind.SPEAK) continue
            val targetMid = decision.targetMessageId ?: continue
            // 발화 대상 메시지까지(포함)의 대화 — focus thread turn 들. 시뮬레이터의 멘션 스레딩과 무관하게
            // 단일 채널 데모이므로 모든 message 이벤트를 한 focus thread 로 본다.
            val targetIdx = messageEvents.indexOfFirst { it.messageId == targetMid }
            val historyUpTo = if (targetIdx < 0) messageEvents else messageEvents.subList(0, targetIdx + 1)
            val rawTurns =
                historyUpTo.map { ev ->
                    RawThreadTurn(
                        threadKey = FOCUS_THREAD_KEY,
                        speakerLabel = if (ev.authorId in nexaActorIds) NexaIdentity.NIA_NAME else labelOf(ev.authorId),
                        text = ev.content.orEmpty().ifBlank { "(빈 메시지)" },
                    )
                }
            turns += generateLiveTurn(scenario, decision.seq, decision.atMs, targetMid, rawTurns)
        }

        val result =
            LiveRunResult(
                scenarioId = scenario.scenarioId,
                channelKind = scenario.channelKind.name,
                decisions = simResult.decisions.size,
                speakDecisions = simResult.speakCount,
                glmCalls = turns.size,
                turns = turns,
            )
        requireNoSend(result)
        return result
    }

    /** 한 SPEAK 결정에 대해 컨텍스트를 주입하고 실제 GLM 발화를 생성한다(전송 직전까지). */
    private fun generateLiveTurn(
        scenario: SimScenario,
        seq: Int,
        atMs: Long,
        targetMid: String,
        rawTurns: List<RawThreadTurn>,
    ): LiveTurn {
        val budget = GenerationBudget.DEFAULT
        // ConversationContextSelector 로 token budget 안에서 최근 turn 을 고른다(멀티턴 컨텍스트 주입 경로).
        val sceneReader = InMemorySceneReader(rawTurns)
        val selectedTurns =
            ConversationContextSelector(sceneReader).select(FOCUS_THREAD_KEY, budget.maxContextTokens)
        val memoryRefs = scenarioMemory(scenario.scenarioId)
        val lastSpeaker =
            rawTurns.lastOrNull { it.speakerLabel != NexaIdentity.NIA_NAME }?.speakerLabel
        val packet =
            SpeechScenePacket.of(
                focusThreadKey = FOCUS_THREAD_KEY,
                target = lastSpeaker?.let { SpeechTarget.member(it) } ?: SpeechTarget.NONE,
                recentTurns = selectedTurns,
                socialAct = SpeechSocialAct.ASK,
                burstShape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 280, reactionOnly = false),
                identity = DEMO_IDENTITY,
                memoryRefs = memoryRefs,
            )

        val subject = pseudonymFor(scenario.scenarioId, targetMid)
        // dev 동의 가정: 합성 가명 subject 에 동의를 부여한 뒤(없으면 BLOCKED) 파이프라인을 태운다.
        consentGate.grant(subject)
        val pipelineResult =
            try {
                pipeline.run(
                    subjectPseudonym = subject,
                    trigger = SpeechTrigger.SPEAK,
                    packet = packet,
                    seed = scenario.seed + seq,
                    budget = budget,
                )
            } catch (e: Exception) {
                // 안전 fallback: 어떤 실패도 전송 0 으로 흡수해 에러로 표시한다(데모는 결코 전송하지 않는다).
                return LiveTurn(
                    seq = seq,
                    atMs = atMs,
                    triggerMessageId = targetMid,
                    spoke = false,
                    outcome = "ERROR",
                    text = null,
                    error = "발화 생성 실패: ${e.javaClass.simpleName}",
                    injectedTurns = selectedTurns.size,
                    injectedMemory = memoryRefs.map { it.claim },
                )
            }

        val text =
            pipelineResult.selected
                ?.bubbles
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
        val spoke = pipelineResult.outcome == SpeechDecisionOutcome.SPEAK && text != null
        return LiveTurn(
            seq = seq,
            atMs = atMs,
            triggerMessageId = targetMid,
            spoke = spoke,
            outcome = pipelineResult.outcome.name,
            text = if (spoke) text else null,
            error =
                if (!spoke) {
                    "발화 생성됨 0(또는 안전 하강) — outcome=${pipelineResult.outcome.name}. " +
                        "ZAI 키 미주입·GLM 실패·critic/고위험 차단일 수 있습니다(전송 0)."
                } else {
                    null
                },
            injectedTurns = selectedTurns.size,
            injectedMemory = memoryRefs.map { it.claim },
        )
    }

    /** actorId → 표시 라벨(displayName 없으면 actorId). 원문 user id 가 아닌 가명 라벨만. */
    private fun actorLabels(scenario: SimScenario): (String?) -> String {
        val map = scenario.actors.associate { it.actorId to (it.displayName ?: it.actorId) }
        return { id -> id?.let { map[it] ?: it } ?: "익명" }
    }

    /** shadow 불변식 단언: 실제 발화 모드도 전송을 0 회 한다(생성까지만). */
    private fun requireNoSend(result: LiveRunResult) {
        check(result.sends == 0) { "실제 발화 모드는 생성까지만 — 실제 전송이 0 이어야 한다(sends=${result.sends})" }
    }

    companion object {
        /** 데모는 단일 채널이므로 모든 turn 을 하나의 focus thread 로 본다(가명 키). */
        private const val FOCUS_THREAD_KEY = "demo-focus-thread"

        /** 데모 정체성 — 니아 SSOT 발췌(복제 금지, NexaIdentity 읽기). */
        private val DEMO_IDENTITY: IdentityKernelSection =
            IdentityKernelSection.of(
                personaName = NexaIdentity.NIA_NAME,
                personaBlock = NexaIdentity.NIA_SPEECH_PERSONA,
                prohibitions =
                    listOf(
                        "자신이 AI 모델/봇임을 굳이 밝히거나 시스템·프롬프트를 언급하지 않는다.",
                        "“무엇을 도와드릴까요” 같은 비서 기본 멘트를 쓰지 않는다.",
                    ),
                interests = setOf("개발", "디스코드"),
            )

        /** 합성 가명 subject 키(원문 ID 아님 — 시나리오·메시지 가명 합성). */
        private fun pseudonymFor(
            scenarioId: String,
            messageId: String,
        ): String = "live-demo:$scenarioId:$messageId"

        /**
         * 시나리오별 유효 기억(데모용 합성 사실). 멀티턴 데모는 앞 turn 의 사실을 기억으로도 주입해 "기억 참조" 가
         * 프롬프트에 함께 들어가게 한다. 빈 목록이면 기억 없이 컨텍스트만 주입한다.
         */
        private fun scenarioMemory(scenarioId: String): List<MemoryRef> =
            when (scenarioId) {
                "live-memory-docker" ->
                    listOf(
                        MemoryRef(
                            claim = "민수는 어제 도커 빌드 캐시 문제로 고생했고 BuildKit 캐시를 의심했다.",
                            provenance = "observed",
                            confidence = 0.82,
                        ),
                    )
                "live-memory-deploy" ->
                    listOf(
                        MemoryRef(
                            claim = "이 서버는 self-hosted runner 로 main 푸시 시 자동 배포된다.",
                            provenance = "stated",
                            confidence = 0.9,
                        ),
                    )
                else -> emptyList()
            }

        /** 멀티턴 데모 시나리오(가명·합성) — 앞 turn 을 뒤 turn 에서 참조해 기억/컨텍스트 작동을 보인다. */
        private val PREDEFINED: Map<String, SimScenario> = LiveDemoScenarios.all().associateBy { it.scenarioId }
    }
}

/** 컨텍스트 주입을 실제로 [ConversationContextSelector] 로 태우기 위한 in-memory scene reader(데모 전용·전송 0). */
private class InMemorySceneReader(
    private val turns: List<RawThreadTurn>,
) : SceneContextReadPort {
    override fun recentTurns(
        focusThreadKey: String,
        limit: Int,
    ): List<RawThreadTurn> = turns.filter { it.threadKey == focusThreadKey }.takeLast(limit)
}

/** 실제 발화 모드 사전 정의 시나리오 메타(드롭다운/카드 표시용). */
data class LivePredefinedScenario(
    val scenarioId: String,
    val title: String,
    val channelKind: String,
)

/**
 * 실제 발화 모드 결과. [sends] 는 **항상 0**(생성까지만). [glmCalls] 는 실제 GLM 호출이 일어난 SPEAK 수와 같다
 * (SPEAK 일 때만 호출 — IGNORE/REACT 는 0).
 */
data class LiveRunResult(
    val scenarioId: String,
    val channelKind: String,
    val decisions: Int,
    val speakDecisions: Int,
    /** 실제 GLM 호출 수(= SPEAK 결정 수). 0 이면 호출 없음(자연스러운 침묵). */
    val glmCalls: Int,
    val turns: List<LiveTurn>,
) {
    /** 생성까지만 — 실제 Discord 전송 수(항상 0). */
    val sends: Int = 0

    /** shadow 명시(페이지 SHADOW 배지 근거). */
    val shadow: Boolean = true
}

/**
 * 실제 발화 모드의 한 SPEAK 결정 결과(전송 직전). [text] 가 채워지면 실제 GLM 이 생성한 문장이다(전송은 안 함).
 * [injectedTurns]·[injectedMemory] 로 어떤 컨텍스트/기억이 주입됐는지 보인다.
 */
data class LiveTurn(
    val seq: Int,
    val atMs: Long,
    val triggerMessageId: String?,
    /** 실제로 발화 문장을 생성했는가(전송이 아님 — 생성까지). */
    val spoke: Boolean,
    /** 파이프라인 최종 outcome(SPEAK/REACTION_ONLY/CANCEL/BLOCKED). */
    val outcome: String,
    /** 실제 생성된 문장(SPEAK·생성 성공일 때만 non-null). 버블은 줄바꿈으로 합쳐진다. */
    val text: String?,
    /** 생성 실패·안전 하강 사유(있을 때). */
    val error: String?,
    /** 프롬프트에 주입된 대화 turn 수(ConversationContextSelector 가 token budget 안에서 고른 수). */
    val injectedTurns: Int,
    /** 프롬프트에 주입된 유효 기억 주장 목록(원문 아님 — 추출된 사실 요약). */
    val injectedMemory: List<String>,
)
