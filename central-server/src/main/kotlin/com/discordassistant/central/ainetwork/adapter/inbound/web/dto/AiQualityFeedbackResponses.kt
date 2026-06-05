package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

import com.discordassistant.central.ainetwork.application.AiFeedbackResult
import com.discordassistant.central.ainetwork.application.AiFeedbackReviewResult
import com.discordassistant.central.ainetwork.application.CandidateQualitySummary
import com.discordassistant.central.ainetwork.application.DashboardAudience

// AI 품질 피드백 API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약).
//
// - 응답 JSON 키·값·순서·null·중첩은 분해 이전과 1바이트도 다르지 않다.
// - audience 기반 마스킹(보안)·providerLabel 프레젠테이션은 CandidateQualityResponse.from 안에만 있다.
// - 컨트롤러는 핸들러 반환 타입(Map/List)을 유지한 채 toMap()/toList() 로 위임한다.
// - 엔티티/리포 의존 0(application 결과만 받는다).

data class SubmitAiFeedbackResponse(
    val saved: AiFeedbackResult,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to saved.id, "status" to saved.status, "rating" to saved.rating)

    companion object {
        fun from(saved: AiFeedbackResult): SubmitAiFeedbackResponse = SubmitAiFeedbackResponse(saved)
    }
}

data class ResolveAiFeedbackResponse(
    val saved: AiFeedbackReviewResult,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to saved.id,
            "status" to saved.status,
            "reviewedBy" to saved.reviewedBy,
            "reviewedAt" to saved.reviewedAt,
        )

    companion object {
        fun from(saved: AiFeedbackReviewResult): ResolveAiFeedbackResponse = ResolveAiFeedbackResponse(saved)
    }
}

/**
 * 후보 품질 요약(audience 마스킹). 본인/관리자만 providerUserId 노출, 공개는 익명 라벨.
 * 반환 원소 타입은 application 의 CandidateQualitySummary 를 그대로 유지한다(컨트롤러 계약 보존).
 */
object CandidateQualityResponse {
    fun from(
        candidates: List<CandidateQualitySummary>,
        audience: DashboardAudience,
    ): List<CandidateQualitySummary> =
        candidates.mapIndexed { index, candidate ->
            candidate.copy(
                providerUserId = if (audience.canSeeProviderIdentity) candidate.providerUserId else null,
                providerLabel =
                    if (audience.canSeeProviderIdentity) {
                        candidate.providerUserId?.let { "provider:$it" } ?: "provider:unknown"
                    } else {
                        "Provider ${index + 1}"
                    },
            )
        }
}
