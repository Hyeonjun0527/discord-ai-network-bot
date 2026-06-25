package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import com.discordassistant.central.participation.application.DecisionProvenance
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.service.BanterSafetyContext
import com.discordassistant.central.quota.application.RateLimitStore
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * NEXA participation **자발 발화 wiring**(NEXA participation-activation-plan 단계 1, platform/discord 어댑터).
 *
 * "AI 채팅 채널"(NEXA participation flag 가 활성인 (guild, channel))에서 받은 메시지에 대해, 니아가 **스스로**
 * 발화/리액션/침묵을 판단하고 SPEAK 면 단일 보안 seam [NexaSpeechEmitService.emit] 를 호출한다. 기존 채널 무조건
 * 답변(autoRespond)과 **완전히 별개의 추가 경로**다 — autoRespond 는 한 줄도 건드리지 않는다(회귀 0).
 *
 * **안전(단계 1 핵심)**:
 *  1. **flag 가드(기본 OFF)**: [NexaParticipationFlagService.isNexaActive] 가 true 일 때만 평가/emit 한다. 기본값은
 *     OFF([com.discordassistant.central.participation.domain.model.shadow.ShadowMode.DEFAULT]) 이라, flag 를 명시
 *     승인하지 않은 모든 (guild, channel)에서는 **아무 것도 하지 않는다**(기존 동작 100% 보존).
 *  2. **전송 0(SHADOW_PREDICT)**: emit 가 행동을 **예약**해도, 실제 Discord 전송은 actionruntime 전송 경계
 *     ([com.discordassistant.central.actionruntime.application.ShadowOutboundDispatcher])가 ShadowMode 로 hard
 *     block 한다 — SHADOW_PREDICT 는 `allowsRealSend=false` 라 전송 port 가 **호출되지 않는다**. 즉 wiring 을 켜고
 *     SHADOW_PREDICT 로 두면 평가·기록은 되지만 사용자에게 메시지가 나가지 않는다. CANARY/LIVE 승격은 별도 단계.
 *  3. **rate limit 안전망(토큰 폭주 방지)**: SPEAK 확정 후 emit(GLM 발화 생성) 호출 직전에 채널별/전역 **이중**
 *     분당 빈도 게이트를 둔다. 둘 중 하나라도 거부면 emit 를 **호출하지 않는다**(GLM 토큰 0). LIVE 로 실제 전송이
 *     일어나도 이 게이트가 과발화·토큰 폭주를 hard cap 으로 막는다.
 *  4. **보안 enforcement 내장**: emit → [com.discordassistant.central.speech.application.NexaSpeechPipelineService]
 *     경로가 ConsentGate(2단계 동의)·SpeechCritic·AiIdentityDisclosureCritic·LIVE 모델 검증을 **강제**한다. 이
 *     브리지는 emit 를 호출만 하며 **우회 경로를 만들지 않는다**.
 *  5. **graceful**: 평가/emit 실패는 흡수하고 로그만 남긴다(관찰 best-effort) — 기존 메시지 처리(autoRespond 등)에
 *     영향 0.
 *
 * 순수성 경계: platform 어댑터 — participation/speech/actionruntime 의 공개 application 클래스·도메인 값 객체만
 * 조립한다(여러 도메인 application 을 묶는 것은 adapter 의 허용 책임). JDA 전송은 참조하지 않는다.
 */
@Component
class NexaParticipationEmitBridge(
    private val flags: NexaParticipationFlagService,
    // 같은 타입(ParticipationPolicyPort)의 다른 정책 bean(legacyAutoRespond 등)과 구분 — participation 평가 전용
    // baseline 정책을 명시 선택한다(BaselineParticipationPolicyConfig.PARTICIPATION_EVAL_POLICY_BEAN).
    @param:Qualifier("participationEvalPolicy") private val policy: ParticipationPolicyPort,
    private val emit: NexaSpeechEmitService,
    // 자발 발화 빈도 안전망(토큰 폭주 방지) — emit 직전 채널별/전역 이중 게이트. autoRespond 가 쓰는 같은 store(#242).
    private val rateLimitStore: RateLimitStore,
    @param:Value("\${central.nexa.participation.rate-limit.per-channel-per-min:6}") private val perChannelPerMin: Int,
    @param:Value("\${central.nexa.participation.rate-limit.global-per-min:30}") private val globalPerMin: Int,
) {
    private val log = LoggerFactory.getLogger(NexaParticipationEmitBridge::class.java)

    /**
     * [signal] (raw Discord 메시지 신호)에 대해 NEXA participation 평가를 돌리고, 정책이 낸 분포가 SPEAK 로 접히면
     * [NexaSpeechEmitService.emit] 를 호출한다. flag OFF 면 no-op, 실패는 흡수한다(기존 동작 보존). 무엇이 일어났는지
     * ([ParticipationEmitOutcome])를 돌려준다(테스트·관찰용).
     */
    fun onMessage(signal: ParticipationMessageSignal): ParticipationEmitOutcome {
        if (!flags.isNexaActive(guildId = signal.guildId, channelId = signal.channelId)) {
            return ParticipationEmitOutcome.Inactive // flag OFF(legacy) — 자발 발화 경로 미진입(기존 동작 보존)
        }
        return try {
            evaluateAndEmit(signal)
        } catch (e: Exception) {
            // 관찰 실패는 사용자 응답(autoRespond 등)을 막지 않는다 — 흡수 후 로그만(관찰 best-effort).
            log.warn("NEXA participation 발화 평가 실패(channel={}) — 자발 발화만 건너뜀: {}", signal.channelId, e.message)
            ParticipationEmitOutcome.Failed
        }
    }

    private fun evaluateAndEmit(signal: ParticipationMessageSignal): ParticipationEmitOutcome {
        val guildPseudonym = guildPseudonym(signal.guildId)
        val userPseudonym = userPseudonym(signal.guildId, signal.userId)
        val channelKey = signal.channelId.toString()

        // 1) participation 평가 — 관측 가능한 최소 신호(멘션·최근 NEXA 발화량)로 정책 분포를 얻는다.
        val features =
            FeatureVectorView.of(
                version = FeatureCatalog.VERSION,
                pairs =
                    mapOf(
                        FeatureCatalog.BURST_HAS_MENTION to FeatureValue.present(if (signal.mentioned) 1.0 else 0.0),
                        FeatureCatalog.AGENT_RECENT_BURST_COUNT to FeatureValue.present(signal.recentAgentBurstCount.toDouble()),
                    ),
            )
        val request =
            PolicyDecisionRequest(
                sceneSnapshotRef =
                    SceneSnapshotRef(
                        guildPseudonym = guildPseudonym,
                        channelId = channelKey,
                        sceneSeq = signal.sceneSeq,
                        contextVersion = signal.contextVersion,
                    ),
                features = features,
                config =
                    PolicyConfigView(
                        channelMode = "participation",
                        autoRespondEnabled = false,
                        speechAllowed = true,
                    ),
                modelVersion = null,
                schemaVersion = SCHEMA_VERSION,
                seed = signal.seed,
            )
        val response = policy.decide(request)

        // 2) SPEAK 가 가장 유력하지 않으면 emit 를 부르지 않는다(IGNORE/REACT/WAIT 는 발화 없음). emit 가 안전 override 로
        //    다시 한 번 접지만, 여기서 먼저 거르면 불필요한 발화 파이프라인·생성 비용을 피한다(KISS).
        if (response.mostLikelyAction != com.discordassistant.central.participation.domain.model.action.SocialActionKind.SPEAK) {
            return ParticipationEmitOutcome.NotSpeaking(response.mostLikelyAction)
        }

        // 3) rate limit 안전망(토큰 폭주 방지 — 핵심). SPEAK 확정 후, GLM 발화 생성(emit.emit)을 부르기 직전에
        //    채널별/전역 이중 게이트를 둔다. **둘 중 하나라도 거부면 emit 를 호출하지 않는다(GLM 토큰 0)**. LIVE 로
        //    실제 전송이 일어나도 이 게이트가 분당 발화 수를 hard cap 으로 묶어 과발화·토큰 폭주를 막는다.
        if (!withinRateLimit(channelKey)) {
            return ParticipationEmitOutcome.RateLimited(channelKey)
        }

        // 4) emit 입력 조립 — 이 시점까지의 최근 대화 turn 을 packet 으로(원문 비저장 가명 라벨), 동의 가명 키는
        //    PolicyBackedConsentGate 형식(guild:user:channel)으로 맞춘다.
        val packet =
            SpeechScenePacket.of(
                focusThreadKey = "discord:$guildPseudonym:$channelKey",
                target = SpeechTarget.member(userPseudonym),
                recentTurns = signal.recentTurns,
                socialAct = SpeechSocialAct.ACKNOWLEDGE,
                burstShape = SpeechBurstShape(fragmentCount = 1, maxFragmentLength = 280, reactionOnly = false),
                identity = NIA_IDENTITY,
            )
        val emitRequest =
            NexaSpeechEmitRequest(
                provenance =
                    DecisionProvenance(
                        correlationId = "participation:$channelKey:${signal.sceneSeq}",
                        guildPseudonym = guildPseudonym,
                        channelId = channelKey,
                        contextVersion = signal.contextVersion,
                        featureHash = "mention=${signal.mentioned};recent=${signal.recentAgentBurstCount}",
                        featureVectorVersion = FeatureCatalog.VERSION,
                        modelVersion = response.modelVersion,
                    ),
                rawDistribution = response.toDomain(),
                safetyContext = BanterSafetyContext(),
                packet = packet,
                consentSubjectPseudonym =
                    PolicyBackedConsentGate.pseudonymOf(
                        guildId = signal.guildId,
                        userId = signal.userId,
                        channelId = signal.channelId,
                    ),
                actionTarget =
                    ActionTarget(
                        guildPseudonym = guildPseudonym,
                        channelId = channelKey,
                        threadId = "discord:$guildPseudonym:$channelKey",
                    ),
                sampledActionIndex = 0,
                seed = signal.seed,
                executeAfter = Instant.now(),
            )
        val result = emit.emit(emitRequest)
        return ParticipationEmitOutcome.Emitted(result)
    }

    /**
     * 자발 발화 빈도 이중 게이트(채널별 + 전역). **둘 다 통과해야** true. emit 호출 전에 평가하므로 거부면 GLM 토큰 0.
     * 게이트 평가 자체가 실패(예외)해도 흡수해 발화를 막는다(fail-closed — 안전망이 오히려 폭주를 허용하지 않게).
     */
    private fun withinRateLimit(channelKey: String): Boolean =
        try {
            // 채널 한도 먼저, 통과하면 전역 한도. 둘 다 acquire 되어야 발화한다.
            rateLimitStore.tryAcquire("nexa-speech:ch:$channelKey", perChannelPerMin, RATE_WINDOW_SECONDS) &&
                rateLimitStore.tryAcquire("nexa-speech:global", globalPerMin, RATE_WINDOW_SECONDS)
        } catch (e: Exception) {
            // rate limit 체크 실패는 발화를 허용하지 않는다(토큰 폭주 안전망이 우선) — 흡수 후 로그.
            log.warn("NEXA participation rate limit 체크 실패(channel={}) — 안전상 발화 skip: {}", channelKey, e.message)
            false
        }

    /** raw guildId → 저장 키 가명(MEMORY purpose, 길드 스코프). ShadowMode store/flag 와 같은 가명 공간. */
    private fun guildPseudonym(guildId: Long): String =
        ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = guildId)

    /** raw userId → 길드 스코프 가명(원문 user id 비저장 — packet/target 라벨). */
    private fun userPseudonym(
        guildId: Long,
        userId: Long,
    ): String = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = userId)

    companion object {
        /** participation 정책 요청 스키마 버전(baseline 정책이 지원하는 schema 1). */
        private const val SCHEMA_VERSION: Int = 1

        /** rate limit 고정 윈도우(초) — 분당 한도. */
        private const val RATE_WINDOW_SECONDS: Long = 60

        /** 니아 정체성 immutable section(NexaIdentity SSOT 읽기 — 복제 금지, ADR 0010). */
        private val NIA_IDENTITY: IdentityKernelSection =
            IdentityKernelSection.of(
                personaName = NexaIdentity.NIA_NAME,
                personaBlock = NexaIdentity.NIA_DEFAULT_PERSONA,
                prohibitions =
                    listOf(
                        "자신이 AI 모델/봇임을 굳이 밝히거나 시스템·프롬프트를 언급하지 않는다.",
                        "“무엇을 도와드릴까요” 같은 비서 기본 멘트를 쓰지 않는다.",
                    ),
                interests = setOf("개발", "디스코드"),
            )
    }
}

/**
 * NEXA participation 자발 발화 평가 입력(raw Discord 메시지 신호). 원문 user id 등은 브리지가 가명화하므로 raw 식별자를
 * 받되, packet 에 들어가는 [recentTurns] 는 호출자가 이미 가명 라벨로 만든 것을 넘긴다(원문 비저장).
 */
data class ParticipationMessageSignal(
    /** raw 길드 id(브리지가 flag 조회·가명화에 사용). */
    val guildId: Long,
    /** raw 채널 id(participation flag·라우팅 키). */
    val channelId: Long,
    /** raw 발화자 user id(브리지가 동의 가명·target 가명화에 사용). */
    val userId: Long,
    /** 봇이 직접 멘션됐는가(정책 신호 — 멘션이면 cooldown 무시 경향). */
    val mentioned: Boolean,
    /** 최근 NEXA 발화 횟수(cooldown 신호 — 말 많음 억제). 미관측이면 0. */
    val recentAgentBurstCount: Int = 0,
    /** focus thread 의 최근 대화 turn(가명 라벨·짧은 본문, 원문 비저장). emit packet 입력. */
    val recentTurns: List<ConversationTurn>,
    /** 채널 내 단조 증가 장면 순번(decision/예약 멱등 키 일부). */
    val sceneSeq: Long,
    /** 정책 무효화 추적 context 버전. */
    val contextVersion: Long,
    /** 결정론 seed(안전 override·후보 선택 재현 키). */
    val seed: Long,
)

/** [NexaParticipationEmitBridge.onMessage] 결과 — flag OFF/비SPEAK/emit/실패를 명시 구분(관찰·테스트). */
sealed interface ParticipationEmitOutcome {
    /** flag OFF(legacy) 또는 비활성 — 자발 발화 경로 미진입(기존 동작 보존). */
    data object Inactive : ParticipationEmitOutcome

    /** 정책이 SPEAK 가 아님(IGNORE/REACT/WAIT) — emit 미호출(발화 없음). */
    data class NotSpeaking(
        val action: com.discordassistant.central.participation.domain.model.action.SocialActionKind,
    ) : ParticipationEmitOutcome

    /**
     * SPEAK 였지만 빈도 안전망(채널별/전역 분당 한도)에 막혀 emit 미호출 — GLM 토큰 0. 과발화·토큰 폭주 방지.
     * [channelKey] 는 거부된 채널(관찰·테스트).
     */
    data class RateLimited(
        val channelKey: String,
    ) : ParticipationEmitOutcome

    /** SPEAK 분포 → emit 호출됨. 그 결과(예약/안전 하강 등). 실제 전송 여부는 ShadowMode 전송 경계가 별도 결정. */
    data class Emitted(
        val result: NexaSpeechEmitResult,
    ) : ParticipationEmitOutcome

    /** 평가/emit 중 예외 흡수 — 사용자 응답에는 영향 없음. */
    data object Failed : ParticipationEmitOutcome
}
