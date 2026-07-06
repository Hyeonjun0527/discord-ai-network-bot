package com.discordassistant.central.discord

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
        val behaviorVersions: AiBehaviorVersionRepository,
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
        fun `setAutoRespond on 은 기존 프로필의 active behavior 가 없으면 기본 behavior 를 복구한다`() {
            channelAis.saveAndFlush(
                ChannelAiEntity(
                    guildId = 100,
                    channelId = 204,
                    displayName = "니아",
                    source = "nia-auto-repair",
                ),
            )

            registry.setAutoRespond(guildId = 100, channelId = 204, on = true, actorId = 5)

            val repaired = channelAis.findByGuildIdAndChannelId(100, 204)!!
            assertTrue(repaired.autoRespond)
            assertNotNull(repaired.activeBehaviorVersionId)
            assertNotNull(behaviorVersions.findByChannelAiIdAndId(repaired.id, repaired.activeBehaviorVersionId!!))
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

        @Test
        fun `setAutoRespond off 는 프로필이 없으면 아무것도 만들지 않고 조용히 반환한다`() {
            // 프로필도 없는 채널에 off 요청 → 프로필 생성 없이 종료, 여전히 false
            registry.setAutoRespond(guildId = 100, channelId = 999, on = false)

            assertFalse(registry.isAutoRespond(100, 999))
            assertNull(profiles.get(100, 999))
        }

        @Test
        fun `invalidateGuild 는 길드 캐시를 무효화해 DB 변경을 다음 조회에 반영한다`() {
            registry.setAutoRespond(100, 400, on = true)
            assertTrue(registry.isAutoRespond(100, 400)) // 캐시 로드

            // DB 에서 직접 삭제 후 invalidateGuild 로 길드 전체 캐시를 비운다.
            channelAis.findByGuildIdAndChannelId(100, 400)?.let { channelAis.delete(it) }
            channelAis.flush()
            registry.invalidateGuild(100)

            assertFalse(registry.isAutoRespond(100, 400)) // 재로드하면 없음
        }
    }
