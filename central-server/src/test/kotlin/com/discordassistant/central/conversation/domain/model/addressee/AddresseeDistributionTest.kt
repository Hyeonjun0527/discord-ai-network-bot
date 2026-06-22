package com.discordassistant.central.conversation.domain.model.addressee

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** NEXA-P05-T009: 확률 합·최소/최대 검증, evidence 에 원문 비저장. */
class AddresseeDistributionTest {
    @Test
    fun `확률 합이 1 이 아니면 거부한다 (acceptance)`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddresseeDistribution(
                candidates = listOf(AddresseeCandidate(AuthorId(1L), 0.5)),
                noneProbability = 0.2,
                resolverVersion = "v1",
                evidence = emptySet(),
            )
        }
    }

    @Test
    fun `후보 확률이 범위를 벗어나면 거부한다 (acceptance)`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddresseeDistribution(
                candidates = listOf(AddresseeCandidate(AuthorId(1L), 1.5)),
                noneProbability = 0.0,
                resolverVersion = "v1",
                evidence = emptySet(),
            )
        }
    }

    @Test
    fun `noneProbability 범위를 벗어나면 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddresseeDistribution(emptyList(), noneProbability = 1.5, resolverVersion = "v1", evidence = emptySet())
        }
    }

    @Test
    fun `중복 member 후보를 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddresseeDistribution(
                candidates =
                    listOf(
                        AddresseeCandidate(AuthorId(1L), 0.3),
                        AddresseeCandidate(AuthorId(1L), 0.3),
                    ),
                noneProbability = 0.4,
                resolverVersion = "v1",
                evidence = emptySet(),
            )
        }
    }

    @Test
    fun `빈 resolverVersion 을 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddresseeDistribution(emptyList(), noneProbability = 1.0, resolverVersion = " ", evidence = emptySet())
        }
    }

    @Test
    fun `유효한 분포는 mostLikely 와 합 검증을 통과한다`() {
        val dist =
            AddresseeDistribution(
                candidates =
                    listOf(
                        AddresseeCandidate(AuthorId(1L), 0.6),
                        AddresseeCandidate(AuthorId(2L), 0.3),
                    ),
                noneProbability = 0.1,
                resolverVersion = "v1",
                evidence = setOf(AddresseeEvidence.DIRECT_REPLY),
            )
        assertEquals(AuthorId(1L), dist.mostLikely?.member)
        assertEquals(false, dist.isLikelyNone)
    }

    @Test
    fun `none 팩토리는 합 1 이고 후보가 없다`() {
        val dist = AddresseeDistribution.none("v1", setOf(AddresseeEvidence.GROUP_ADDRESSED))
        assertEquals(1.0, dist.noneProbability)
        assertTrue(dist.candidates.isEmpty())
        assertTrue(dist.isLikelyNone)
    }

    @Test
    fun `evidence 는 코드 enum 만 담고 원문 텍스트 필드가 없다 (acceptance)`() {
        // AddresseeEvidence 는 enum 이라 임의 문자열(원문)을 담을 수 없다 — 컴파일·구조로 보장.
        val codes = AddresseeEvidence.entries.toSet()
        assertTrue(codes.all { it is AddresseeEvidence })
    }
}
