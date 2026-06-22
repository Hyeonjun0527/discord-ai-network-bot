package com.discordassistant.central.actionruntime.application.port.out

import com.discordassistant.central.actionruntime.domain.model.ActionAuditEvent

/**
 * 예약 행동 감사 로그 아웃바운드 포트(NEXA-P13-T022, application 레이어).
 *
 * 모든 실행/취소/실패 상태 변경을 **append-only** 로 남긴다. 구현(JPA)은 한 번 쓴 레코드를 수정/삭제하지 않는다 —
 * 감사 무결성. acceptance(T022): "원문 없이 decision/action/message IDs 로 사건을 재구성할 수 있다." — 따라서
 * [ActionAuditEvent] 는 원문을 담지 않고, 이 포트는 ID·phase·reason·시각만 기록한다.
 *
 * 순수성 경계: application 레이어 — 도메인 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ActionAuditPort {
    /** [event] 를 감사 로그에 **추가**한다(append-only — 기존 레코드 불변). */
    fun append(event: ActionAuditEvent)

    /** [actionId] 의 감사 사건을 **시간순**으로 모두 돌려준다(한 행동의 생애 재구성 — 검증·사후 분석). */
    fun findByAction(actionId: String): List<ActionAuditEvent>
}
