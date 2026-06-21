package com.discordassistant.central.actionruntime.domain

import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.actionruntime.domain.service.CancellationPolicy
import com.discordassistant.central.actionruntime.domain.service.CancellationVerdict
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P13-T012/T013 — CancellationPolicy: 다른 인간 응답·주제 전환·대상 만료 취소가 scene evidence 를 쓰는지,
 * 단순 새 메시지 하나나 다른 동시 thread 활동이 잘못된 취소를 만들지 않는지 단위 테스트.
 */
class CancellationPolicyTest {
    private val policy = CancellationPolicy()
    private val target = ActionTarget(guildPseudonym = "g1", channelId = "c1", threadId = "t1")

    private fun speak() =
        ScheduledSocialAction.create(
            decisionId = "d1",
            sampledActionIndex = 0,
            type = ScheduledActionType.SPEAK,
            target = target,
            executeAfter = Instant.EPOCH,
            contextVersion = 1,
        )

    private fun evidence(
        replies: Int = 0,
        focus: String? = "t1",
        expired: Boolean = false,
    ) = SceneEvidence(humanRepliesSinceSchedule = replies, currentFocusThreadId = focus, targetExpired = expired)

    // ── T012: 다른 인간 응답 취소(scene evidence, 단순 1건은 취소 안 함) ──

    @Test
    fun `새 메시지 하나만으로는 취소하지 않는다(보수적)`() {
        assertThat(policy.decide(speak(), evidence(replies = 1))).isEqualTo(CancellationVerdict.KEEP)
    }

    @Test
    fun `충분한 인간 응답이면 취소 후보가 된다`() {
        assertThat(policy.decide(speak(), evidence(replies = 2)))
            .isEqualTo(CancellationVerdict.CANCEL_OTHER_HUMAN_ANSWERED)
    }

    // ── T013: 주제 전환·대상 만료 ──

    @Test
    fun `focus thread 가 target 밖으로 이동하면 주제 전환 취소다`() {
        assertThat(policy.decide(speak(), evidence(focus = "t2")))
            .isEqualTo(CancellationVerdict.CANCEL_TOPIC_SWITCHED)
    }

    @Test
    fun `focus 가 여전히 target thread 면 취소하지 않는다(다른 동시 thread 활동 무관)`() {
        assertThat(policy.decide(speak(), evidence(focus = "t1"))).isEqualTo(CancellationVerdict.KEEP)
    }

    @Test
    fun `focus 관찰이 없으면(null) 주제 전환 취소를 만들지 않는다`() {
        assertThat(policy.decide(speak(), evidence(focus = null))).isEqualTo(CancellationVerdict.KEEP)
    }

    @Test
    fun `대상 만료는 가장 강한 취소 신호다`() {
        assertThat(policy.decide(speak(), evidence(expired = true)))
            .isEqualTo(CancellationVerdict.CANCEL_TARGET_EXPIRED)
    }

    @Test
    fun `비-SPEAK 행동은 취소 정책 대상이 아니다`() {
        val react =
            ScheduledSocialAction.create(
                decisionId = "d2",
                sampledActionIndex = 0,
                type = ScheduledActionType.REACT,
                target = target,
                executeAfter = Instant.EPOCH,
                contextVersion = 1,
            )
        assertThat(policy.decide(react, evidence(replies = 9, focus = "t2", expired = true)))
            .isEqualTo(CancellationVerdict.KEEP)
    }
}
