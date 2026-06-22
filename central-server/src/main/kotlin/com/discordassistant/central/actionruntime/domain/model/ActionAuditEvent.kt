package com.discordassistant.central.actionruntime.domain.model

import java.time.Instant

/**
 * 예약 사회적 행동의 **감사 사건**(NEXA-P13-T022, 순수 도메인 값 객체·불변·append-only).
 *
 * schedule→generate→typing→send/cancel/fail 의 **모든 상태 변경**을 한 줄씩 남긴다. acceptance(T022): "원문 없이
 * decision/action/message IDs 로 사건을 재구성할 수 있다." — 따라서 이 레코드는 **원문(발화 본문)을 담지 않는다**.
 * 식별 참조([actionId]=ActionIdentity 값, [decisionId], 선택적 [messageId]=전송된 Discord 메시지 ID)와 무슨 일이
 * 일어났는지([phase])·왜([reason], 취소/실패 사유 코드)만 담는다.
 *
 * - **누가/언제/왜**: [phase](무슨 단계)·[occurredAt](언제)·[reason](왜, 취소·실패 사유 wireName) — 사람이 아니라
 *   시스템 행위 주체이므로 actor 는 [actionId] 가 가리키는 decision 으로 환원된다(원문·PII 비포함).
 * - **재구성 가능**: 같은 [actionId] 의 레코드를 시간순으로 모으면 한 행동의 전 생애(예약→생성→typing→전송/취소)를
 *   원문 없이 복원할 수 있다.
 *
 * 순수성: Spring/JPA/JDA 미참조(actionruntime.domain 규칙, NexaArchitectureTest.nexaDomainsArePure).
 */
data class ActionAuditEvent(
    /** 어느 예약 행동인가(ActionIdentity.value = "<decisionId>#<index>"). */
    val actionId: String,
    /** 이 행동을 만든 participation 결정 식별자(원문 비포함). */
    val decisionId: String,
    /** 어느 단계의 사건인가(상태 변경의 종류). */
    val phase: ActionAuditPhase,
    /** 전송된 Discord 메시지 ID(SENT 단계에서만 non-null — message ID 로 사건 추적, T017 acceptance). */
    val messageId: String? = null,
    /** 취소/실패 사유 코드(해당 단계에서만 non-null — wireName 등 안정 코드, 원문 아님). */
    val reason: String? = null,
    /** 사건 발생 시각. */
    val occurredAt: Instant,
) {
    init {
        require(actionId.isNotBlank()) { "actionId 는 비어 있을 수 없다" }
        require(decisionId.isNotBlank()) { "decisionId 는 비어 있을 수 없다" }
    }
}

/**
 * 감사 사건의 단계(NEXA-P13-T022, 순수 도메인 enum). schedule→generate→typing→send/cancel/fail 의 각 상태 변경을
 * 안정 코드로 분류한다 — append-only 로그를 phase 시퀀스로 읽어 한 행동의 생애를 재구성한다.
 */
enum class ActionAuditPhase(
    /** persistence·로그 직렬화용 안정 코드. */
    val wireName: String,
) {
    /** 예약됨(SCHEDULED). */
    SCHEDULED("scheduled"),

    /** 발화 본문 생성됨(speech generate 완료 — 본문은 별도, 여기엔 ID 만). */
    GENERATED("generated"),

    /** typing indicator 시작(P12 typing plan 실행). */
    TYPING_STARTED("typing_started"),

    /** typing indicator 종료(발화 도착/취소/maxDuration 도래 중 가장 이른 시점). */
    TYPING_STOPPED("typing_stopped"),

    /** 한 버블 전송됨(messageId 연결 — burst 의 각 버블마다 한 줄). */
    SENT("sent"),

    /** 모든 버블 전송 완료(COMPLETED 종결). */
    COMPLETED("completed"),

    /** 부분 전송 후 잔여 버블 취소됨(PARTIALLY_SENT — T020, reason 연결). */
    PARTIALLY_CANCELLED("partially_cancelled"),

    /** 취소됨(다른 인간 응답/주제 전환/동의 철회/stale — reason 연결, T012~T014). */
    CANCELLED("cancelled"),

    /** 영구 실패(reason=ActionFailureReason.wireName — T009/T018). */
    FAILED("failed"),

    /** shadow 단계라 전송이 hard block 됨(OBSERVE_ONLY 등 — 전송 0회 증거, P09). */
    SUPPRESSED_SHADOW("suppressed_shadow"),
}
