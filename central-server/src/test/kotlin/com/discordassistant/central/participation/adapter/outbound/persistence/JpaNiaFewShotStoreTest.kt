package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotLookupScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScopeType
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaNiaFewShotStore::class)
class JpaNiaFewShotStoreTest
    @Autowired
    constructor(
        private val store: JpaNiaFewShotStore,
    ) {
        @Test
        fun `draft can be published and then resolved as active few-shot source`() {
            val draft = store.createDraft(NiaFewShotScope.global(), listOf(example()), actorUserId = 10)
            val published = store.publish(draft.setId!!, draft.version, reviewerUserId = 20)

            val active = store.findActive(NiaFewShotLookupScope(guildId = 1, channelId = 2))
            val activeVersion = active!!.active!!
            val activeExample = activeVersion.examples.single()

            assertThat(published.activeVersion).isEqualTo(1)
            assertThat(activeVersion.status).isEqualTo(NiaFewShotVersionStatus.ACTIVE)
            assertThat(activeExample.rawMessages.single().text)
                .isEqualTo("raw-canary direct request")
        }

        @Test
        fun `published versions cannot be edited by draft replacement`() {
            val draft = store.createDraft(NiaFewShotScope.global(), listOf(example()), actorUserId = null)
            store.publish(draft.setId!!, draft.version, reviewerUserId = null)

            assertThatThrownBy {
                store.replaceDraftExamples(draft.setId!!, draft.version, listOf(example("replacement", priority = 99)))
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("immutable")
        }

        @Test
        fun `publishing a new version archives the previous active version and rollback restores it`() {
            val first = store.createDraft(NiaFewShotScope.global(), listOf(example("v1", priority = 1)), actorUserId = 10)
            store.publish(first.setId!!, first.version, reviewerUserId = 20)

            val second = store.createDraft(NiaFewShotScope.global(), listOf(example("v2", priority = 2)), actorUserId = 10)
            val afterSecondPublish = store.publish(second.setId!!, second.version, reviewerUserId = 20)

            assertThat(afterSecondPublish.activeVersion).isEqualTo(2)
            assertThat(afterSecondPublish.versions.first { it.version == 1 }.status)
                .isEqualTo(NiaFewShotVersionStatus.ARCHIVED)

            val afterRollback = store.rollback(first.setId!!, targetVersion = 1, reviewerUserId = 30)

            assertThat(afterRollback.activeVersion).isEqualTo(1)
            assertThat(afterRollback.versions.first { it.version == 1 }.status)
                .isEqualTo(NiaFewShotVersionStatus.ACTIVE)
            assertThat(afterRollback.versions.first { it.version == 2 }.status)
                .isEqualTo(NiaFewShotVersionStatus.ARCHIVED)
            assertThat(afterRollback.active!!.rollbackOfVersion).isEqualTo(2)
        }

        @Test
        fun `active lookup chooses channel scope before broader scopes`() {
            val global = store.createDraft(NiaFewShotScope.global(), listOf(example("global")), actorUserId = null)
            store.publish(global.setId!!, global.version, reviewerUserId = null)

            val guild =
                store.createDraft(
                    NiaFewShotScope(NiaFewShotScopeType.GUILD, guildId = 100, persona = "nia"),
                    listOf(example("guild")),
                    actorUserId = null,
                )
            store.publish(guild.setId!!, guild.version, reviewerUserId = null)

            val channel =
                store.createDraft(
                    NiaFewShotScope(NiaFewShotScopeType.CHANNEL, guildId = 100, channelId = 200, persona = "nia"),
                    listOf(example("channel")),
                    actorUserId = null,
                )
            store.publish(channel.setId!!, channel.version, reviewerUserId = null)

            val active = store.findActive(NiaFewShotLookupScope(guildId = 100, channelId = 200, persona = "nia"))
            val activeExample = active!!.active!!.examples.single()

            assertThat(active.scope.stableKey).isEqualTo("channel:100:200:nia")
            assertThat(activeExample.title).isEqualTo("channel")
        }

        @Test
        fun `entity toString does not leak raw message text`() {
            val entity =
                NiaFewShotExampleEntity(
                    id = 1,
                    versionId = 2,
                    title = "example",
                    rawMessagesJson = """[{"text":"raw-secret-canary"}]""",
                    expectedAction = NiaFewShotAction.SPEAK.name,
                    reason = "reason",
                    evidenceRefsJson = "[]",
                    badAlternativeJson = "{}",
                    tagsJson = "[]",
                    priority = 0,
                )

            assertThat(entity.toString()).doesNotContain("raw-secret-canary")
        }

        @Test
        fun `action-specific few-shot payloads survive persistence round trip`() {
            val react =
                example("reaction").copy(
                    expectedAction = NiaFewShotAction.REACT,
                    currentState = "The latest message needs acknowledgement without another message.",
                    expectedReactionCode = "eyes",
                    badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.SPEAK, "speech would interrupt the flow"),
                )
            val draft = store.createDraft(NiaFewShotScope.global(), listOf(react), actorUserId = null)

            val stored =
                store
                    .findSet(draft.setId!!)!!
                    .versions
                    .single()
                    .examples
                    .single()

            assertThat(stored.currentState).isEqualTo(react.currentState)
            assertThat(stored.expectedReactionCode).isEqualTo("eyes")
            assertThat(stored.expectedReevaluateAfterMs).isNull()
        }

        private fun example(
            title: String = "direct request",
            priority: Int = 10,
        ): NiaFewShotExample =
            NiaFewShotExample(
                title = title,
                rawMessages =
                    listOf(
                        NiaFewShotRawMessage(
                            ref = "m1",
                            authorRole = "member",
                            offsetMs = 0,
                            text = "raw-canary direct request",
                        ),
                    ),
                expectedAction = NiaFewShotAction.SPEAK,
                reason = "The user directly asks Nia to respond, so the judge should speak.",
                evidenceRefs = setOf("m1"),
                badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.WAIT, "waiting would ignore the direct ask"),
                tags = setOf("direct-ask"),
                priority = priority,
            )
    }
