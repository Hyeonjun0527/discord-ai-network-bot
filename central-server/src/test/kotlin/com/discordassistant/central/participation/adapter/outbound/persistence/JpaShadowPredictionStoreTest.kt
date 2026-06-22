package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.port.out.ShadowPredictionRecord
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * NEXA-P09-T009 shadow 예측 persistence(Flyway V59) acceptance 단위 테스트(H2 Postgres 모드).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaShadowPredictionStore::class)
class JpaShadowPredictionStoreTest
    @Autowired
    constructor(
        val store: JpaShadowPredictionStore,
    ) {
        private val scene = SceneKey(guildPseudonym = "g-1", channelId = "c-1", sceneSeq = 5)

        @Test
        fun `acceptance — 동일 scene 에서 여러 baseline 결과를 비교할 수 있다`() {
            store.append(prediction("baseline-always-silent-1", SocialActionKind.IGNORE, speak = 0.0))
            store.append(prediction("baseline-mention-always-speak-1", SocialActionKind.SPEAK, speak = 1.0))
            store.append(prediction("legacy-auto-respond-1", SocialActionKind.SPEAK, speak = 1.0))

            val all = store.findByScene(scene)
            assertThat(all).hasSize(3)
            assertThat(all.map { it.modelVersion })
                .containsExactlyInAnyOrder(
                    "baseline-always-silent-1",
                    "baseline-mention-always-speak-1",
                    "legacy-auto-respond-1",
                )
            // 정책별 분포가 보존되어 비교 가능.
            val silent = all.first { it.modelVersion == "baseline-always-silent-1" }
            assertThat(silent.actionWeights[SocialActionKind.IGNORE]).isEqualTo(1.0)
        }

        @Test
        fun `같은 (scene, 정책) 재기록은 멱등(1건으로 수렴)`() {
            store.append(prediction("baseline-fixed-probability-1", SocialActionKind.IGNORE, speak = 0.1))
            store.append(prediction("baseline-fixed-probability-1", SocialActionKind.SPEAK, speak = 0.9)) // 갱신
            val all = store.findByScene(scene)
            assertThat(all).hasSize(1)
            assertThat(all.first().sampledAction).isEqualTo(SocialActionKind.SPEAK)
        }

        @Test
        fun `예상 발사 시점과 contextVersion 이 라운드트립으로 보존된다`() {
            val fireAt = Instant.parse("2026-06-22T01:02:03Z")
            store.append(prediction("legacy-auto-respond-1", SocialActionKind.SPEAK, speak = 1.0, expectedFireAt = fireAt))
            val found = store.findByScene(scene).first()
            assertThat(found.expectedFireAt).isEqualTo(fireAt)
            assertThat(found.contextVersion).isEqualTo(9L)
        }

        @Test
        fun `요약과 보존(purge)`() {
            store.append(prediction("p-old", SocialActionKind.IGNORE, speak = 0.0, predictedAt = Instant.now().minus(40, ChronoUnit.DAYS)))
            store.append(prediction("p-new", SocialActionKind.SPEAK, speak = 1.0, predictedAt = Instant.now()))
            val summary = store.summarizeGuild("g-1")
            assertThat(summary.predictionCount).isEqualTo(2)

            val purged = store.purgeExpired(Instant.now().minus(30, ChronoUnit.DAYS))
            assertThat(purged).isEqualTo(1)
            assertThat(store.summarizeGuild("g-1").predictionCount).isEqualTo(1)
        }

        private fun prediction(
            modelVersion: String,
            sampled: SocialActionKind,
            speak: Double,
            expectedFireAt: Instant? = null,
            predictedAt: Instant = Instant.now(),
        ): ShadowPredictionRecord =
            ShadowPredictionRecord(
                scene = scene,
                contextVersion = 9,
                modelVersion = modelVersion,
                actionWeights = mapOf(SocialActionKind.SPEAK to speak, SocialActionKind.IGNORE to (1.0 - speak)),
                sampledAction = sampled,
                expectedFireAt = expectedFireAt,
                seed = 42L,
                featureHash = "hash-$modelVersion",
                featureVectorVersion = 1,
                predictedAt = predictedAt,
            )
    }
