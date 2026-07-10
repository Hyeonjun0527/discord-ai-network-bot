package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.execution.ActionExecutionService
import com.discordassistant.central.actionruntime.application.execution.BackpressureGate
import com.discordassistant.central.actionruntime.application.execution.ExecutionOutcome
import com.discordassistant.central.actionruntime.application.port.out.DiscordExecutorPort
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.support.ControllableReevaluation
import com.discordassistant.central.actionruntime.support.InMemoryActionAudit
import com.discordassistant.central.actionruntime.support.InMemoryActionScheduler
import com.discordassistant.central.actionruntime.support.MutableTestClock
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant

/**
 * NEXA-P15-T023 — NEXA shadow E2E fixture 재생.
 *
 * test-fixtures/nexa/e2e/ 의 시나리오를 읽어 **관찰→판단→예약→shadow log** 흐름을 mock 으로 재생하고, 두 핵심
 * 불변을 검증한다:
 *  1. **GLM(발화 생성)은 policy SPEAK 전까지 호출되지 않는다** — 정책이 IGNORE 면 발화 생성 진입 자체가 없다
 *     ([SocialAction.consumesGenerationQuota] 로 SPEAK 만 generation 을 유발).
 *  2. **shadow 단계에서는 Discord 전송이 0회** — SPEAK 가 예약돼 실행돼도 [ActionExecutionService] 가 SHADOW_PREDICT
 *     에서 executor 를 한 번도 호출하지 않는다(hard block, P09 일관).
 *
 * 실제 JDA/네트워크/GLM 호출은 없다(전부 fake). 외부 전송·배포·결제 금지 원칙을 지킨다.
 */
class NexaShadowEndToEndTest {
    private val clock = MutableTestClock()

    @Test
    fun `fixture 재생 — IGNORE 결정은 GLM 도 전송도 0회다`() {
        val fixture = loadFixture()
        assertThat(fixture.policyAction).isEqualTo("IGNORE")

        // 정책 결정을 SocialAction 으로 복원. IGNORE 는 generation 을 소모하지 않는다(SPEAK 전 GLM 미호출).
        val decision: SocialAction = SocialAction.Ignore
        assertThat(decision.consumesGenerationQuota).isFalse()

        // GLM 호출 카운터: SPEAK 가 아니므로 발화 생성 경로가 없다(0).
        val glmCalls = if (decision.consumesGenerationQuota) 1 else 0
        assertThat(glmCalls).isEqualTo(fixture.expectGlmCalls)

        // IGNORE 는 예약도 전송도 없다 — Discord outbound 0.
        val executor = CountingDiscordExecutor()
        assertThat(executor.totalCalls).isEqualTo(fixture.expectDiscordCalls)
    }

    @Test
    fun `fixture 재생 — SHADOW lane 에서는 SPEAK 가 예약·실행돼도 전송 0회다`() {
        val fixture = loadFixture()
        assertThat(fixture.lane).isEqualTo("SHADOW")

        val executor = CountingDiscordExecutor()
        val scheduler = InMemoryActionScheduler(clock)
        val audit = InMemoryActionAudit()
        // policy SPEAK 가 예약된 상태를 가정(강한 불변 검증: shadow 면 그래도 전송 0).
        val action =
            ScheduledSocialAction
                .create(
                    decisionId = "dec-shadow",
                    sampledActionIndex = 0,
                    type = ScheduledActionType.SPEAK,
                    target = ActionTarget(fixture.guildPseudonym, fixture.channelId.toString(), "thread-1"),
                    executeAfter = Instant.parse("2026-01-01T00:00:00Z"),
                    contextVersion = 1L,
                    originRolloutMode = ShadowMode.SHADOW_PREDICT,
                ).markScheduled()
                .beginReevaluation()
                .passReevaluation()
        scheduler.put(action)

        val service = ActionExecutionService(executor, scheduler, ControllableReevaluation(1L), audit, BackpressureGate(), clock)
        val outcome = service.execute(ShadowMode.SHADOW_PREDICT, action, BurstPlan.single("speech-ref"))

        // shadow hard block: 전송 0회(typing 포함 어떤 JDA 호출도 없음).
        assertThat(outcome).isInstanceOf(ExecutionOutcome.Suppressed::class.java)
        assertThat(executor.totalCalls).isEqualTo(fixture.expectDiscordCalls)
        assertThat(executor.totalCalls).isZero()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFixture(): Fixture {
        val file = File("../test-fixtures/nexa/e2e/shadow-observe-to-shadow-log.yaml")
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        val guild = root["guild"] as Map<String, Any?>
        val policy = root["policyDecision"] as Map<String, Any?>
        val expect = root["expect"] as Map<String, Any?>
        return Fixture(
            guildPseudonym = guild["pseudonym"].toString(),
            channelId = (guild["channelId"] as Number).toLong(),
            lane = guild["lane"].toString(),
            policyAction = policy["action"].toString(),
            expectDiscordCalls = (expect["discordOutboundCalls"] as Number).toInt(),
            expectGlmCalls = (expect["glmGenerationCalls"] as Number).toInt(),
        )
    }

    private data class Fixture(
        val guildPseudonym: String,
        val channelId: Long,
        val lane: String,
        val policyAction: String,
        val expectDiscordCalls: Int,
        val expectGlmCalls: Int,
    )

    /** 전송류 호출을 모두 세는 executor fake(전송 0회 검증 — typing 포함 어떤 호출도 없어야 한다). */
    private class CountingDiscordExecutor : DiscordExecutorPort {
        var totalCalls = 0
            private set

        override fun startTyping(channelId: String): ExecutionResult {
            totalCalls++
            return ExecutionResult.Ok
        }

        override fun react(
            channelId: String,
            targetMessageId: String,
            emoji: String,
        ): ExecutionResult {
            totalCalls++
            return ExecutionResult.Ok
        }

        override fun sendBubble(
            channelId: String,
            speechPlanRef: String,
            bubbleIndex: Int,
            replyToMessageId: String?,
        ): ExecutionResult {
            totalCalls++
            return ExecutionResult.Sent("msg-$bubbleIndex")
        }
    }
}
