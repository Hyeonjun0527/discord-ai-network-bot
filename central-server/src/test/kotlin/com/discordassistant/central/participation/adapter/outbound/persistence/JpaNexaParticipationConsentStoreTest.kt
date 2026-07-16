package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.conversation.adapter.outbound.persistence.DbConsentPolicyAdapter
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaNexaParticipationConsentStore::class, DbConsentPolicyAdapter::class)
class JpaNexaParticipationConsentStoreTest
    @Autowired
    constructor(
        private val store: JpaNexaParticipationConsentStore,
        private val consentPolicy: DbConsentPolicyAdapter,
        private val guildConsents: NexaGuildConsentRepository,
        private val channelScopes: NexaChannelConsentScopeRepository,
    ) {
        @Test
        fun `LIVE 활성화는 guild와 channel consent를 함께 열고 actor를 기록한다`() {
            store.activateMemberChannel(guildId = 10L, channelId = 20L, actorId = 30L)

            assertThat(consentPolicy.observationDecision(guildId = 10L, userId = 40L, channelId = 20L))
                .isEqualTo(ConsentDecision.OBSERVE_AND_SPEAK)
            assertThat(guildConsents.findByGuildId(10L)?.consentedBy).isEqualTo(30L)
            assertThat(guildConsents.findByGuildId(10L)?.speakMode).isEqualTo("MEMBER")
        }

        @Test
        fun `채널 비활성화는 해당 scope만 닫고 다른 LIVE 채널은 보존한다`() {
            store.activateMemberChannel(guildId = 11L, channelId = 21L, actorId = 31L)
            store.activateMemberChannel(guildId = 11L, channelId = 22L, actorId = 31L)

            store.deactivateMemberChannel(guildId = 11L, channelId = 21L)

            assertThat(consentPolicy.observationDecision(guildId = 11L, userId = 41L, channelId = 21L))
                .isEqualTo(ConsentDecision.DENIED)
            assertThat(consentPolicy.observationDecision(guildId = 11L, userId = 41L, channelId = 22L))
                .isEqualTo(ConsentDecision.OBSERVE_AND_SPEAK)
        }

        @Test
        fun `채널 cleanup은 scope를 지우고 guild revoke는 모든 scope를 닫는다`() {
            store.activateMemberChannel(guildId = 12L, channelId = 23L, actorId = 32L)
            store.activateMemberChannel(guildId = 12L, channelId = 24L, actorId = 32L)

            store.clearChannel(guildId = 12L, channelId = 23L)
            store.revokeGuild(guildId = 12L)

            assertThat(channelScopes.findByGuildIdAndChannelId(12L, 23L)).isNull()
            assertThat(guildConsents.findByGuildId(12L)?.enabled).isFalse()
            assertThat(channelScopes.findByGuildIdAndChannelId(12L, 24L)?.observeAllowed).isFalse()
            assertThat(channelScopes.findByGuildIdAndChannelId(12L, 24L)?.speakAllowed).isFalse()
        }
    }
