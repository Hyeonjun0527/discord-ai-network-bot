package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.provider.domain.model.ProviderState
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 라우팅 성능 회귀 가드(차수 16 #249). 다수 후보에 대한 filter→select 를 반복 실행해
 * 치명적 성능 회귀(예: O(n^2) 실수)를 잡는다. 절대 시간이 아니라 넉넉한 상한으로 catastrophe 만 차단.
 */
class RoutingBenchmarkTest {
    private val pipe = ProviderFilterPipeline()
    private val router = ProviderRouter()

    private fun candidates(n: Int): List<Candidate> =
        (1..n).map { id ->
            Candidate(
                providerId = id.toLong(),
                state = ProviderState.ONLINE_IDLE,
                supportedBurdens = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD),
                maxConcurrency = 4,
                activeRequests = id % 3,
                remainingDaily = 100,
                failureRate = (id % 10) / 100.0,
                recentHandled = id % 7,
            )
        }

    @Test
    fun `대량 후보 filter+select 성능 가드`() {
        val pool = candidates(2000)
        val ctx = RequestContext(ModelBurden.LIGHT, setOf(1L), 200L, 50)
        // 워밍업
        repeat(5) { router.select(pipe.filter(pool, ctx).eligible, ctx) }

        val start = System.nanoTime()
        val iterations = 200
        repeat(iterations) {
            val eligible = pipe.filter(pool, ctx).eligible
            assertNotNull(router.select(eligible, ctx))
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 2000후보 × 200회 = 40만 후보 평가. 넉넉한 상한 10초(회귀 catastrophe 차단).
        assertTrue(elapsedMs < 10_000, "라우팅 성능 회귀 의심: ${elapsedMs}ms (2000×200)")
    }
}
