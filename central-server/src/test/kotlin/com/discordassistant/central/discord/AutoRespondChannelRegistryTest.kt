package com.discordassistant.central.discord

import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AutoRespondChannelRegistry::class, ChannelAiProfileService::class)
class AutoRespondChannelRegistryTest
    @Autowired
    constructor(
        val registry: AutoRespondChannelRegistry,
        val profiles: ChannelAiProfileService,
        val channelAis: ChannelAiRepository,
    ) {
        @Test
        fun `setAutoRespond 는 프로필이 없으면 니아 기본으로 생성하고 자동응답을 켠다`() {
            assertFalse(registry.isAutoRespond(100, 200))

            registry.setAutoRespond(guildId = 100, channelId = 200, on = true, actorId = 5)

            assertTrue(registry.isAutoRespond(100, 200))
            // 프로필(채널 AI 행)이 니아 기본으로 만들어져 페르소나 응답이 가능하다.
            assertNotNull(profiles.get(100, 200))
            assertTrue(channelAis.findByGuildIdAndChannelId(100, 200)!!.autoRespond)
        }

        @Test
        fun `setAutoRespond off 는 자동응답을 끄고 캐시에 즉시 반영된다`() {
            registry.setAutoRespond(100, 201, on = true)
            assertTrue(registry.isAutoRespond(100, 201))

            registry.setAutoRespond(100, 201, on = false)

            assertFalse(registry.isAutoRespond(100, 201)) // 캐시 무효화 → 재로드로 false
        }

        @Test
        fun `자동응답이 꺼진 채널은 캐시 조회에서 false 다`() {
            profiles.set(100, 202, "니아", avatarUrl = null) // 프로필만 있고 자동응답 미설정
            assertFalse(registry.isAutoRespond(100, 202))
        }

        @Test
        fun `채널 캐시 무효화 후 DB 변경이 다음 조회에 반영된다`() {
            registry.setAutoRespond(100, 203, on = true)
            assertTrue(registry.isAutoRespond(100, 203)) // 캐시 로드

            // 외부 경로(채널 삭제 정리)가 행을 지운 뒤 캐시를 무효화하면 다음 조회에서 false 가 되어야 한다.
            channelAis.findByGuildIdAndChannelId(100, 203)?.let { channelAis.delete(it) }
            channelAis.flush()
            registry.invalidateChannel(100, 203)

            assertFalse(registry.isAutoRespond(100, 203))
        }

        @Test
        fun `길드 캐시는 서로 격리된다`() {
            registry.setAutoRespond(100, 300, on = true)
            registry.setAutoRespond(200, 300, on = false) // 다른 길드 같은 채널 id → 만들 것 없음(off)

            assertTrue(registry.isAutoRespond(100, 300))
            assertFalse(registry.isAutoRespond(200, 300))
        }
    }
