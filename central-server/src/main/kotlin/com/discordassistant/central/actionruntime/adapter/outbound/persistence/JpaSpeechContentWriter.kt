package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.out.SpeechContentWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * [SpeechContentWriter] 의 JPA 구현(NEXA-P13-T003/T017, outbound persistence 어댑터).
 *
 * 확정된 발화 본문을 `nexa_scheduled_action_content` 에 참조(actionIdentity)로 저장한다. **멱등**: 같은 참조 행이
 * 이미 있으면 쓰지 않는다(원문 이중 저장/덮어쓰기 금지 — 같은 결정 재처리·at-least-once 안전). 전송 직전
 * [JpaSpeechContentResolver] 가 이 행을 읽어 본문으로 푼다.
 *
 * 순수성: application 포트 계약 ↔ entity 매핑만. 도메인은 이 어댑터를 모른다(헥사고날).
 */
@Component
class JpaSpeechContentWriter(
    private val content: ScheduledActionContentRepository,
    private val clock: Clock = Clock.systemUTC(),
) : SpeechContentWriter {
    @Transactional
    override fun store(
        speechPlanRef: String,
        content: String,
    ) {
        // 멱등 — 같은 참조가 이미 있으면 no-op(원문 이중 저장 금지, T003).
        if (this.content.findByActionIdentity(speechPlanRef) != null) return
        this.content.save(
            ScheduledActionContentEntity(
                actionIdentity = speechPlanRef,
                content = content,
                createdAt = Instant.now(clock),
            ),
        )
    }
}
