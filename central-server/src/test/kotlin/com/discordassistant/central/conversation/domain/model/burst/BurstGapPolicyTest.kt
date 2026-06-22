package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.application.port.out.BurstGapConfigPort
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * NEXA-P04-T005 acceptance: 설정 부재 시 안전한 기본값이 적용되고 런타임 중 변경은 새 fragment 부터 반영된다.
 */
class BurstGapPolicyTest {
    @Test
    fun `clamp 는 minGap floor 와 maxGap ceiling 으로 자른다`() {
        val policy = BurstGapPolicy.DEFAULT
        assertEquals(policy.minGap, policy.clamp(Duration.ofMillis(1)))
        assertEquals(policy.maxGap, policy.clamp(Duration.ofHours(1)))
        assertEquals(policy.defaultGap, policy.clamp(policy.defaultGap))
    }

    @Test
    fun `불변식 위반은 생성 시 거부된다`() {
        assertThatThrownBy {
            BurstGapPolicy(defaultGap = Duration.ofSeconds(1), minGap = Duration.ofSeconds(5), maxGap = Duration.ofSeconds(10))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            BurstGapPolicy(defaultGap = Duration.ofSeconds(10), minGap = Duration.ofSeconds(2), maxGap = Duration.ofSeconds(5))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `설정 부재 시 resolve 는 안전 기본값을 적용한다`() {
        val emptyPort =
            object : BurstGapConfigPort {
                override fun policyFor(channelId: ChannelId): BurstGapPolicy? = null
            }
        assertEquals(BurstGapPolicy.DEFAULT, emptyPort.resolve(ChannelId(100L)))
    }

    @Test
    fun `런타임 변경은 다음 조회부터 반영된다 (포트는 매 조회 현재값 반환)`() {
        val custom = BurstGapPolicy(Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(9))
        var current: BurstGapPolicy? = null
        val mutablePort =
            object : BurstGapConfigPort {
                override fun policyFor(channelId: ChannelId): BurstGapPolicy? = current
            }
        // 변경 전: 기본값.
        assertEquals(BurstGapPolicy.DEFAULT, mutablePort.resolve(ChannelId(100L)))
        // 런타임 변경 후: 다음 조회부터 새 값.
        current = custom
        assertEquals(custom, mutablePort.resolve(ChannelId(100L)))
    }
}
