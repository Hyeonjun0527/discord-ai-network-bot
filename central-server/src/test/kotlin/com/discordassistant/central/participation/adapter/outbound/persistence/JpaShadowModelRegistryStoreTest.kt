package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.model.ModelStatus
import com.discordassistant.central.participation.application.model.ShadowModelCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant

/**
 * NEXA-P11-T020 shadow 모델 레지스트리 persistence(Flyway V62) acceptance 단위 테스트(H2 Postgres 모드).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaShadowModelRegistryStore::class)
class JpaShadowModelRegistryStoreTest
    @Autowired
    constructor(
        val store: JpaShadowModelRegistryStore,
    ) {
        private fun candidate(
            id: String,
            status: ModelStatus = ModelStatus.REGISTERED,
        ): ShadowModelCandidate =
            ShadowModelCandidate(
                modelId = id,
                artifactSha256 = "sha-$id",
                modelVersion = "v1",
                featureSchemaVersion = 1,
                calibrationVersion = "cal-1",
                status = status,
                registeredAt = Instant.parse("2026-06-22T00:00:00Z"),
            )

        @Test
        fun `없는 modelId 는 null`() {
            assertThat(store.find("nope")).isNull()
        }

        @Test
        fun `등록 후 조회하면 같은 값을 돌려준다`() {
            store.save(candidate("m-1"))
            val found = store.find("m-1")
            assertThat(found).isNotNull
            assertThat(found!!.artifactSha256).isEqualTo("sha-m-1")
            assertThat(found.status).isEqualTo(ModelStatus.REGISTERED)
        }

        @Test
        fun `같은 modelId 재저장은 상태를 갱신한다(중복 행 생성 안 함)`() {
            store.save(candidate("m-1"))
            store.save(candidate("m-1", ModelStatus.SHADOW))
            assertThat(store.find("m-1")!!.status).isEqualTo(ModelStatus.SHADOW)
            assertThat(store.listAll().count { it.modelId == "m-1" }).isEqualTo(1)
        }

        @Test
        fun `listAll 은 등록된 모든 후보를 돌려준다`() {
            store.save(candidate("a"))
            store.save(candidate("b"))
            assertThat(store.listAll().map { it.modelId }).containsExactlyInAnyOrder("a", "b")
        }
    }
