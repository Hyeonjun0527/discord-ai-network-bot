package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import java.time.Instant

/**
 * 길드별 shadow 단계 상태·전이 audit 저장 포트(NEXA-P09-T007, application 레이어).
 *
 * **기본값 OFF(acceptance T007)**: 행이 없으면 [currentMode] 가 [ShadowMode.OFF] 를 돌려준다(명시 승인 전에는
 * 어떤 정책도 안 돌아감). [applyTransition] 은 새 단계를 저장하고 [ShadowModeAudit] 를 append-only 로 남긴다.
 *
 * 순수성 경계: application 레이어 — 도메인 타입·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ShadowModeStorePort {
    /** [guildPseudonym] 의 현재 단계(행이 없으면 [ShadowMode.OFF] — 기본 OFF). */
    fun currentMode(guildPseudonym: String): ShadowMode

    /** 검증된 전이([audit])를 적용한다 — 현재 단계를 [ShadowModeAudit.to] 로 갱신하고 audit 를 append. */
    fun applyTransition(audit: ShadowModeAudit)

    /** [guildPseudonym] 의 전이 이력(최신 우선) — 감사 조회. */
    fun auditTrail(guildPseudonym: String): List<ShadowModeAudit>

    /** 현재 단계가 있는 모든 길드의 (가명, 단계, 마지막 변경 시각) — 관리자 상태 API(T010) 목록. */
    fun listModes(): List<ShadowModeState>
}

/**
 * 한 길드의 현재 shadow 단계 상태(application 값 객체). 관리자 상태 API 목록·요약에 쓰인다.
 */
data class ShadowModeState(
    val guildPseudonym: String,
    val mode: ShadowMode,
    val updatedAt: Instant,
)
