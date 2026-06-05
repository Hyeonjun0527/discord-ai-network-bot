package com.discordassistant.central.preset.adapter.inbound.web.dto

import com.discordassistant.central.preset.application.PresetBehaviorInput

// 요청 DTO (인바운드 웹 어댑터). 입력은 application 의 *Input DTO 만 참조한다(엔티티/리포지토리 의존 금지).
// 필드/기본값은 컨트롤러에 인라인이던 원본과 1:1 동일 — JSON 바인딩 계약 불변.

data class CreatePresetRequest(
    val actorUserId: Long? = null,
    val name: String,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class SaveChannelPresetRequest(
    val actorUserId: Long? = null,
    val name: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
)

data class UpdatePresetRequest(
    val actorUserId: Long? = null,
    val name: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val visibility: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class PublishPresetRequest(
    val actorUserId: Long? = null,
    val title: String? = null,
    val description: String? = null,
)

data class UpdatePublishedPresetRequest(
    val actorUserId: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val behavior: PresetBehaviorInput? = null,
)

data class ImportPresetRequest(
    val targetGuildId: Long,
    val targetChannelId: Long? = null,
    val actorUserId: Long? = null,
    val confirmConflicts: Boolean = false,
)

data class LikePresetRequest(
    val userId: Long,
)

data class ReportPresetRequest(
    val reporterUserId: Long? = null,
    val reason: String? = null,
    val reasonCode: String? = null,
    val details: String? = null,
)

data class ReviewPresetReportRequest(
    val decision: String,
    val reviewerUserId: Long? = null,
)
