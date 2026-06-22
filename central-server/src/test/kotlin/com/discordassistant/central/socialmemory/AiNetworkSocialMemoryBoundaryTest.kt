package com.discordassistant.central.socialmemory

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository
import com.discordassistant.central.socialmemory.adapter.outbound.ainetwork.JpaNiaAffinityBridge
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Instant

/**
 * NEXA-P06-T021 ainetwork 중복 쓰기 방지 테스트(아키텍처 + 통합).
 *
 * deliverable: 같은 개념(호감도/관계)을 두 컨텍스트가 동시에 업데이트하지 않는지 증명한다.
 * acceptance: 한 이벤트가 호감도/관계 상태에 중복 side effect 를 만들지 않는다.
 *
 * 두 갈래로 증명한다:
 * 1. **아키텍처**: socialmemory 는 ainetwork 호감도 **쓰기 경로**(NiaAffinityService·user_affinity 변경 쿼리)에
 *    의존하지 않는다 — socialmemory 가 호감도를 갱신할 코드 경로가 타입 수준에서 존재하지 않는다(ADR 0010 BRIDGE).
 * 2. **통합**: 읽기 브리지([JpaNiaAffinityBridge])를 N 번 호출해도 user_affinity 행이 전혀 바뀌지 않는다 — 읽기는
 *    side effect 가 없다(중복 쓰기 부재).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiNetworkSocialMemoryBoundaryTest
    @Autowired
    constructor(
        private val affinities: UserAffinityRepository,
    ) {
        // ── 1. 아키텍처: socialmemory 는 ainetwork 호감도 쓰기 경로에 의존하지 않는다 ──
        @Test
        fun `socialmemory 는 ainetwork 호감도 writer 에 의존하지 않는다`() {
            val imported =
                ClassFileImporter()
                    .withImportOption(ImportOption.DoNotIncludeTests())
                    .importPackages("com.discordassistant.central")
            // socialmemory 는 ainetwork 호감도 쓰기 서비스(NiaAffinityService)를 import 하지 않는다.
            val rule =
                noClasses()
                    .that()
                    .resideInAnyPackage("..central.socialmemory..")
                    .should()
                    .dependOnClassesThat()
                    .haveNameMatching(".*ainetwork.*NiaAffinityService.*")
                    .allowEmptyShould(true)
            rule.check(imported)
        }

        @Test
        fun `socialmemory domain 은 ainetwork 를 전혀 import 하지 않는다 (ADR 0010 read bridge 만 adapter)`() {
            val imported =
                ClassFileImporter()
                    .withImportOption(ImportOption.DoNotIncludeTests())
                    .importPackages("com.discordassistant.central")
            val rule =
                noClasses()
                    .that()
                    .resideInAnyPackage("..central.socialmemory.domain..", "..central.socialmemory.application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..central.ainetwork..")
                    .allowEmptyShould(true)
            rule.check(imported)
        }

        // ── 2. 통합: 읽기 브리지는 user_affinity 를 변경하지 않는다(중복 side effect 부재) ──
        @Test
        fun `읽기 브리지를 여러 번 호출해도 user_affinity 가 변하지 않는다 (중복 쓰기 부재)`() {
            val userId = 4242L
            val saved =
                affinities.save(
                    UserAffinityEntity(
                        userId = userId,
                        score = 70,
                        stage = "FRIENDLY",
                        stageOrdinal = 2,
                        lastInteractionAt = Instant.parse("2026-01-01T00:00:00Z"),
                        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )
            val bridge = JpaNiaAffinityBridge(affinities)
            val countBefore = affinities.count()

            val view = bridge.affinityView(userId)!!
            assertEquals(2, view.stageOrdinal)
            // 여러 번 읽어도 행 수·score·stage 가 변하지 않는다(delta 기반 — 공유 H2 의 타 테스트 행과 무관).
            repeat(5) { bridge.affinityView(userId) }
            val reloaded = affinities.findByUserId(userId)!!
            assertEquals(countBefore, affinities.count(), "읽기 브리지는 새 행을 만들지 않는다")
            assertEquals(saved.score, reloaded.score, "읽기 브리지는 score 를 변경하지 않는다")
            assertEquals(saved.stageOrdinal, reloaded.stageOrdinal, "읽기 브리지는 stage 를 변경하지 않는다")
        }

        @Test
        fun `호감도가 없는 사용자는 null 뷰를 받는다 (복제 저장하지 않음)`() {
            val bridge = JpaNiaAffinityBridge(affinities)
            val absentUser = 987654321L
            val countBefore = affinities.count()
            assertNull(bridge.affinityView(absentUser), "없는 사용자는 null 뷰")
            assertEquals(countBefore, affinities.count(), "조회만으로 행이 생기지 않는다")
        }
    }
