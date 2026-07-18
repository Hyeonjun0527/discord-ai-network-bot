package com.discordassistant.central.socialpolicy

import com.discordassistant.central.socialmemory.adapter.outbound.persistence.JpaPendingIntentStore
import com.discordassistant.central.socialmemory.adapter.outbound.persistence.PendingIntentEntityRepository
import com.discordassistant.central.socialmemory.adapter.outbound.persistence.PendingIntentSourceEventRepository
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.intent.IntentActivation
import com.discordassistant.central.socialmemory.domain.model.intent.IntentUrgency
import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import com.discordassistant.central.socialmemory.domain.model.intent.SocialAct
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.JpaInteractionOutcomeStore
import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.JpaSceneBeliefState
import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.ObservedInteractionOutcomeRepository
import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.SceneBeliefStateRepository
import com.discordassistant.central.socialpolicy.adapter.outbound.persistence.UnresolvedInteractionRepository
import com.discordassistant.central.socialpolicy.application.InteractionOutcomeRetentionService
import com.discordassistant.central.socialpolicy.application.port.out.SceneObservation
import com.discordassistant.central.socialpolicy.domain.model.InteractionEvidenceRef
import com.discordassistant.central.socialpolicy.domain.model.ObservedOutcomeCode
import com.discordassistant.central.socialpolicy.domain.model.RecentInteractionOutcomeBelief
import com.discordassistant.central.socialpolicy.domain.model.UnresolvedInteraction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaSceneBeliefState::class, JpaPendingIntentStore::class, JpaInteractionOutcomeStore::class)
class JpaClosedLoopStateTest
    @Autowired
    constructor(
        private val scene: JpaSceneBeliefState,
        private val pending: JpaPendingIntentStore,
        private val outcomes: JpaInteractionOutcomeStore,
        private val sceneRows: SceneBeliefStateRepository,
        private val pendingRows: PendingIntentEntityRepository,
        private val sourceRows: PendingIntentSourceEventRepository,
        private val interactionRows: UnresolvedInteractionRepository,
        private val outcomeRows: ObservedInteractionOutcomeRepository,
    ) {
        private val now = Instant.parse("2026-07-17T00:00:00Z")

        @Test
        fun `장면 observation은 같은 evidence에 멱등이고 새 evidence에 context를 전진한다`() {
            val first = scene.observe(SceneObservation("g", "c", "focus", 1, 1, "m1", now))
            val duplicate = scene.observe(SceneObservation("g", "c", "focus", 1, 1, "m1", now))
            val next = scene.observe(SceneObservation("g", "c", "focus", 2, 2, "m2", now.plusSeconds(1)))

            assertThat(first.contextVersion).isEqualTo(1)
            assertThat(duplicate.contextVersion).isEqualTo(1)
            assertThat(next.contextVersion).isEqualTo(2)
            assertThat(sceneRows.count()).isEqualTo(1)
        }

        @Test
        fun `관찰된 사람 반응은 다음 judge가 읽는 장면 결과에 남는다`() {
            scene.observe(SceneObservation("g", "c", "focus", 1, 1, "m1", now))

            scene.recordOutcome(
                "focus",
                RecentInteractionOutcomeBelief("a1", "repetition_complaint", "m2", now.plusSeconds(1)),
            )

            assertThat(scene.find("focus")?.recentOutcomes)
                .singleElement()
                .extracting(RecentInteractionOutcomeBelief::code)
                .isEqualTo("repetition_complaint")
        }

        @Test
        fun `PendingIntent는 재조회되고 완료 뒤 active 조회에서 빠진다`() {
            val intent =
                PendingIntent(
                    id = "promise-1",
                    visibility = VisibilityScope.Channel("g", "c"),
                    topic = "재미있는 이야기",
                    targetPseudonym = "u",
                    socialAct = SocialAct.TELL_STORY,
                    activation = IntentActivation.IMMEDIATE,
                    urgency = IntentUrgency.NORMAL,
                    source = MemorySource(setOf("m1"), 1, true, now),
                    expiresAt = now.plusSeconds(3600),
                    confidence = 0.83,
                    focusThreadKey = "focus",
                )
            pending.save(intent)

            val reloaded = pending.findActive("focus", now).single()
            assertThat(reloaded.topic).isEqualTo("재미있는 이야기")
            assertThat(reloaded.confidence).isEqualTo(0.83)
            val completed = pending.complete("promise-1", now.plusSeconds(10), "action-1")
            assertThat(completed?.status).isEqualTo(MemoryStatus.COMPLETED)
            assertThat(completed?.completedByActionId).isEqualTo("action-1")
            assertThat(pending.findActive("focus", now.plusSeconds(11))).isEmpty()
            assertThat(pendingRows.count()).isEqualTo(1)
            assertThat(sourceRows.count()).isEqualTo(1)
        }

        @Test
        fun `완료된 PendingIntent는 같은 id의 ACTIVE 갱신으로 부활하지 않는다`() {
            val active =
                PendingIntent(
                    id = "promise-terminal",
                    visibility = VisibilityScope.Channel("g", "c"),
                    topic = "재미있는 이야기",
                    targetPseudonym = "u",
                    socialAct = SocialAct.TELL_STORY,
                    activation = IntentActivation.IMMEDIATE,
                    urgency = IntentUrgency.NORMAL,
                    source = MemorySource(setOf("m1"), 1, true, now),
                    expiresAt = now.plusSeconds(3600),
                    focusThreadKey = "focus",
                )
            pending.save(active)
            pending.complete(active.id, now.plusSeconds(1), "action-terminal")

            val attemptedResurrection = pending.save(active.copy(topic = "다시 열린 약속"))

            assertThat(attemptedResurrection.status).isEqualTo(MemoryStatus.COMPLETED)
            assertThat(pending.findActive("focus", now.plusSeconds(2))).isEmpty()
            assertThat(pendingRows.findById(active.id).orElseThrow().topic).isEqualTo("재미있는 이야기")
        }

        @Test
        fun `실행 행동의 다음 같은-focus 사람 반응을 outcome으로 귀속한다`() {
            outcomes.open(UnresolvedInteraction("a1", "focus", "speak", null, "scheduled_action:a1", null, now, now.plusSeconds(60)))

            val observed = outcomes.observeLatest("focus", ObservedOutcomeCode.REPETITION_COMPLAINT, "m2", null, now.plusSeconds(5))

            assertThat(observed?.actionId).isEqualTo("a1")
            assertThat(interactionRows.findByActionId("a1")?.status).isEqualTo("RESOLVED")
            assertThat(outcomeRows.count()).isEqualTo(1)
        }

        @Test
        fun `행동 결과 retention은 outcome을 먼저 지우고 만료 interaction만 삭제한다`() {
            val expired =
                UnresolvedInteraction(
                    actionId = "old-action",
                    focusThreadKey = "old-focus",
                    actionKind = "send",
                    intentSummary = "old",
                    sourceEvidenceRef = "old-source",
                    sentMessageRef = null,
                    openedAt = now.minus(Duration.ofDays(50)),
                    expiresAt = now.minus(Duration.ofDays(40)),
                )
            val retained =
                expired.copy(
                    actionId = "new-action",
                    focusThreadKey = "new-focus",
                    sourceEvidenceRef = "new-source",
                    sentMessageRef = null,
                    openedAt = now.minus(Duration.ofDays(20)),
                    expiresAt = now.minus(Duration.ofDays(10)),
                )
            outcomes.open(expired)
            outcomes.observeLatest(
                focusThreadKey = "old-focus",
                code = ObservedOutcomeCode.POSITIVE_FEEDBACK,
                evidenceRef = "old-reaction",
                replyToMessageRef = null,
                observedAt = now.minus(Duration.ofDays(45)),
            )
            outcomes.open(retained)

            val retention =
                InteractionOutcomeRetentionService(
                    interactionRows,
                    outcomeRows,
                    Clock.fixed(now, ZoneOffset.UTC),
                )

            assertThat(retention.purgeExpired(30, now)).isEqualTo(1)
            assertThat(interactionRows.findByActionId("old-action")).isNull()
            assertThat(outcomeRows.findByEvidenceRef("old-reaction")).isEmpty()
            assertThat(interactionRows.findByActionId("new-action")).isNotNull()
        }

        @Test
        fun `reply 대상이 있으면 더 최근 행동보다 그 메시지를 보낸 행동에 귀속한다`() {
            val firstRef = InteractionEvidenceRef.discordMessage("101")
            val secondRef = InteractionEvidenceRef.discordMessage("202")
            outcomes.open(UnresolvedInteraction("a1", "focus", "speak", null, "scheduled_action:a1", firstRef, now, now.plusSeconds(60)))
            outcomes.open(
                UnresolvedInteraction(
                    "a2",
                    "focus",
                    "speak",
                    null,
                    "scheduled_action:a2",
                    secondRef,
                    now.plusSeconds(1),
                    now.plusSeconds(60),
                ),
            )

            val observed =
                outcomes.observeLatest(
                    "focus",
                    ObservedOutcomeCode.HUMAN_FOLLOW_UP,
                    "m3",
                    firstRef,
                    now.plusSeconds(5),
                )

            assertThat(observed?.actionId).isEqualTo("a1")
            assertThat(interactionRows.findByActionId("a1")?.status).isEqualTo("RESOLVED")
            assertThat(interactionRows.findByActionId("a2")?.status).isEqualTo("OPEN")
        }

        @Test
        fun `일치하지 않는 reply는 무관한 최신 행동에 귀속하지 않는다`() {
            val known = InteractionEvidenceRef.discordMessage("known")
            val unknown = InteractionEvidenceRef.discordMessage("unknown")
            outcomes.open(UnresolvedInteraction("a1", "focus", "speak", null, "scheduled_action:a1", known, now, now.plusSeconds(60)))

            val observed =
                outcomes.observeLatest(
                    "focus",
                    ObservedOutcomeCode.HUMAN_FOLLOW_UP,
                    "m2",
                    unknown,
                    now.plusSeconds(5),
                )

            assertThat(observed).isNull()
            assertThat(interactionRows.findByActionId("a1")?.status).isEqualTo("OPEN")
            assertThat(outcomeRows.count()).isZero()
        }
    }
