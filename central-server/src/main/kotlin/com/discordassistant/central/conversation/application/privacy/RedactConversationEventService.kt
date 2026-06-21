package com.discordassistant.central.conversation.application.privacy

import com.discordassistant.central.conversation.application.port.out.EventStorePort
import com.discordassistant.central.conversation.domain.model.event.EventId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * conversation event store redaction 유스케이스(NEXA-P03-T022, security 핵심).
 *
 * 삭제 트리거(메시지 삭제 / 사용자 옵트아웃 / 길드 탈퇴 / 동의 철회, deletion-propagation.md)가 도착하면 이 유스케이스가
 * 대상 이벤트의 **암호화 content payload 를 무효화**하고(존재·순서·계보는 보존), **provenance(어느 트리거가 언제)와
 * 처리 증거(처리 시각·결과)** 만 남긴다. 행을 지우지 않고 [EventStorePort.markRedacted] 로 상태만 전이하므로 event
 * sourcing 의 순서·계보가 깨지지 않는다.
 *
 * **원문·식별자 미보존(data-categories.md 불변식 3, redaction-contract.md)**: 이 유스케이스는 원문 텍스트·작성자
 * snowflake 원문·키를 보존하지 않는다. [RedactionReceipt] 는 가명/암호화 참조도 만들지 않고 **이벤트 키·트리거 코드·
 * 시각** 만 담는다(원문 평문 부재를 타입으로 강제).
 *
 * **비가역 hash 보존 — 법무 검토 OPEN(BLOCKER 추적, deletion-propagation.md T009)**: "삭제 후 비가역 hash 보존" 의
 * 법적 적합성은 법무 검토 미확정(OPEN)이다. 그래서 이 구현은 **hash 를 보존하지 않는다**(최소 입장) — receipt 는
 * 비가역 hash 도, 원문/식별자도 담지 않고 처리 증거(키·트리거·시각)만 남긴다. 법무가 hash 보존을 허용하면 그때
 * 비가역 hash+시각만 추가하도록 [RedactionReceipt] 를 확장한다(현재는 의도적으로 미구현).
 *
 * **replay 일관성(acceptance T022)**: redaction 후 [EventStorePort.streamByChannel] 의 그 이벤트는 redacted=true 라
 * content 가 항상 unavailable 로 보인다(content_cipher=null). replay 는 redaction 상태를 일관되게 관찰한다.
 *
 * application 레이어 — 포트([EventStorePort])로만 결합한다. @Transactional 로 markRedacted 가 단일 경계에서 일어난다.
 */
@Service
class RedactConversationEventService(
    private val eventStore: EventStorePort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * [eventId] 이벤트를 [trigger] 사유로 redaction 한다. 암호화 payload 를 무효화하고 provenance+처리 증거만 남긴다.
     *
     * 멱등: 이미 redaction 됐거나 대상이 없으면 [RedactionOutcome.ALREADY_REDACTED_OR_ABSENT](중복 side effect 없음).
     * 처음 redaction 이면 [RedactionOutcome.REDACTED] 와 함께 원문/식별자 없는 [RedactionReceipt] 를 돌려준다.
     */
    @Transactional
    fun redact(
        eventId: EventId,
        trigger: RedactionTrigger,
    ): RedactionResult {
        val transitioned = eventStore.markRedacted(eventId)
        return if (transitioned) {
            RedactionResult(
                outcome = RedactionOutcome.REDACTED,
                receipt =
                    RedactionReceipt(
                        eventId = eventId,
                        trigger = trigger,
                        processedAt = clock.instant(),
                    ),
            )
        } else {
            RedactionResult(outcome = RedactionOutcome.ALREADY_REDACTED_OR_ABSENT, receipt = null)
        }
    }
}

/**
 * redaction 처리 증거(provenance) — **원문·식별자·가명·hash 를 일절 담지 않는다**(redaction-contract.md 금지 필드 부재).
 *
 * 어느 이벤트가([eventId]) 어떤 트리거로([trigger]) 언제([processedAt]) redaction 됐는지만 기록한다. 이 타입은
 * 원문/snowflake 원문/키/비가역 hash 를 운반할 필드가 **구조적으로 없다** — 회귀 테스트가 그 부재를 증명한다.
 *
 * 법무 검토(deletion-propagation.md T009 OPEN)가 비가역 hash 보존을 허용하기 전까지 hash 필드는 두지 않는다(보수적).
 */
data class RedactionReceipt(
    /** redaction 된 이벤트의 멱등 키(원문 아님 — EventIdentity.key() 형식). */
    val eventId: EventId,
    /** redaction 을 유발한 삭제 트리거(코드만; 원문/PII 아님). */
    val trigger: RedactionTrigger,
    /** redaction 처리 시각(주입된 Clock — 처리 증거). */
    val processedAt: Instant,
)

/** redaction 실행 결과 — outcome + (처음 redaction 시) 원문 없는 처리 증거. */
data class RedactionResult(
    val outcome: RedactionOutcome,
    /** 처음 redaction 일 때만 채워진다(멱등 흡수 시 null). */
    val receipt: RedactionReceipt?,
)

/** [RedactConversationEventService.redact] 결과 구분(멱등). */
enum class RedactionOutcome {
    /** 처음 redaction — content payload 무효화, 처리 증거 생성. */
    REDACTED,

    /** 이미 redaction 됐거나 대상이 없음 — 멱등 흡수(추가 side effect 없음). */
    ALREADY_REDACTED_OR_ABSENT,
}

/**
 * redaction 을 유발한 삭제 트리거(deletion-propagation.md 4종). provenance 코드일 뿐 원문/PII 가 아니다.
 */
enum class RedactionTrigger {
    /** Discord 메시지 삭제 — 해당 이벤트 원문 제거. */
    MESSAGE_DELETED,

    /** 사용자 삭제 요청 — 그 사용자 이벤트 원문 제거. */
    USER_DELETION_REQUEST,

    /** 길드 탈퇴/봇 제거 — 길드 스코프 이벤트 원문 제거. */
    GUILD_REMOVAL,

    /** 동의 철회 — 기존 수집분 원문 제거. */
    CONSENT_WITHDRAWAL,
}
