package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

/**
 * NEXA-P15-T002 채널별 participation flag persistence(Flyway V65) acceptance 단위 테스트(H2 Postgres 모드).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaNexaParticipationFlagStore::class)
class JpaNexaParticipationFlagStoreTest
    @Autowired
    constructor(
        val store: JpaNexaParticipationFlagStore,
    ) {
        @Test
        fun `acceptance — 설정이 없으면 override null + 제외 빈 집합(기존 동작 보존)`() {
            assertThat(store.channelOverride("g-x", 100L)).isNull()
            assertThat(store.excludedChannelIds("g-x")).isEmpty()
        }

        @Test
        fun `채널 override 를 저장하고 읽는다`() {
            store.setChannelOverride("g-1", 100L, ParticipationLane.SHADOW)
            assertThat(store.channelOverride("g-1", 100L)).isEqualTo(ParticipationLane.SHADOW)
            // 다른 채널은 영향 없음.
            assertThat(store.channelOverride("g-1", 200L)).isNull()
        }

        @Test
        fun `override 를 null 로 지우면 행이 제거되어 길드 lane 상속으로 돌아간다`() {
            store.setChannelOverride("g-1", 100L, ParticipationLane.LIVE)
            store.setChannelOverride("g-1", 100L, null)
            assertThat(store.channelOverride("g-1", 100L)).isNull()
        }

        @Test
        fun `제외 채널(kill switch)을 켜고 끈다`() {
            store.setChannelExcluded("g-1", 100L, true)
            store.setChannelExcluded("g-1", 200L, true)
            assertThat(store.excludedChannelIds("g-1")).containsExactlyInAnyOrder(100L, 200L)

            store.setChannelExcluded("g-1", 100L, false)
            assertThat(store.excludedChannelIds("g-1")).containsExactly(200L)
        }

        @Test
        fun `override 와 제외가 한 행에 공존한다`() {
            store.setChannelOverride("g-1", 100L, ParticipationLane.CANARY)
            store.setChannelExcluded("g-1", 100L, true)
            assertThat(store.channelOverride("g-1", 100L)).isEqualTo(ParticipationLane.CANARY)
            assertThat(store.excludedChannelIds("g-1")).containsExactly(100L)
        }
    }
