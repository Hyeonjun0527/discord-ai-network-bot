package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ProviderState
import org.springframework.stereotype.Component

data class Selection(
    val providerId: Long,
    val score: Double,
    val reason: String,
)

/**
 * 공정성 점수 & 최종 Provider 선택 (K-차수 10, specs §8/§15).
 *
 * provider_score = 적합도 + idle + 남은 한도 − 실패율 − 현재 부하 − heavy 낭비 패널티 − 최근 과다처리.
 * light 요청에 heavy provider 를 기본 낭비하지 않는다(패널티). 동점은 최근 처리량 적은 쪽으로 분산.
 */
@Component
class ProviderRouter {
    fun score(
        c: Candidate,
        ctx: RequestContext,
    ): Double {
        var s = 10.0 // 적합도(이미 burden 통과)
        if (c.state == ProviderState.ONLINE_IDLE) s += 5.0
        s += (minOf(c.remainingDaily, 100) / 100.0) * 5.0 // 남은 한도
        s -= c.failureRate * 10.0 // 실패율
        s -= c.activeRequests * 3.0 // 현재 부하
        s -= c.recentHandled * 2.0 // 최근 과다처리(공정성)
        // heavy 낭비 패널티: 가벼운 요청에 heavy 전용 provider 를 쓰면 감점
        val top = c.supportedBurdens.maxByOrNull { it.ordinal } ?: ModelBurden.LIGHT
        if (ctx.requiredBurden == ModelBurden.LIGHT && top == ModelBurden.HEAVY) s -= 8.0
        // 수준 일치 보너스(light→light, standard→standard 우선)
        if (top == ctx.requiredBurden) s += 2.0
        return s
    }

    /** 후보 중 최종 1인 선택. 비면 null. 동점은 (점수↓, 최근처리량↑ 적은 순, providerId↑)로 결정. */
    fun select(
        candidates: List<Candidate>,
        ctx: RequestContext,
    ): Selection? {
        if (candidates.isEmpty()) return null
        val best =
            candidates
                .map { it to score(it, ctx) }
                .sortedWith(
                    compareByDescending<Pair<Candidate, Double>> { it.second }
                        .thenBy { it.first.recentHandled }
                        .thenBy { it.first.providerId },
                ).first()
        return Selection(best.first.providerId, best.second, "점수 ${"%.1f".format(best.second)}")
    }
}
