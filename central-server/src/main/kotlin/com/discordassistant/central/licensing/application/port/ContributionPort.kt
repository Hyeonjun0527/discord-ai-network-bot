package com.discordassistant.central.licensing.application.port

/**
 * 유저의 풀 기여 실적 조회 아웃바운드 포트(ADR 0005, 이벤트 자격). 구현은 requestlog 의 contribution_log 조회.
 */
fun interface ContributionPort {
    /** [userId]가 풀에 1건 이상 기여(요청 처리)했는가 — 런칭 이벤트(평생 무료) 신청 자격. */
    fun hasContributed(userId: Long): Boolean
}
