package com.discordassistant.central.routing.domain.service

import com.discordassistant.central.routing.domain.model.RoutingScoreBreakdown

/**
 * 후보별 라우팅 스코어 산출 추상화(DIP). 상위 정책([ProviderRouter])이 구체 모델 대신 이 포트에 의존해
 * 스코어 모델을 교체/주입할 수 있다. 기본 구현은 [HaloGfScoreModel].
 */
interface ScoreModel {
    fun scoreResult(
        c: Candidate,
        ctx: RequestContext,
    ): RoutingScoreBreakdown
}
