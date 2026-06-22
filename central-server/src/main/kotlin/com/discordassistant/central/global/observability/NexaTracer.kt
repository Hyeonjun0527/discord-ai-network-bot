package com.discordassistant.central.global.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Component

/**
 * NEXA application 경계 trace instrumentation(NEXA-P18-T003).
 *
 * 주요 application 경계(event 수집·정책 추론·예약·생성·외부 routing/Discord 호출)에 **span** 을 둔다. Micrometer
 * [ObservationRegistry] 를 쓰므로 `micrometer-tracing-bridge-otel` 로 자동 OTel span 이 된다(build.gradle.kts:64,
 * 기존 분산추적 instrumentation 패턴 따름). 미설정 환경(샘플링 0·NOOP registry)에서는 비용 0 으로 동작한다.
 *
 * **acceptance(T003) — IGNORE 경로와 SPEAK 경로의 비용 차이를 trace 에서 확인할 수 있다**:
 *  - [span] 은 흐름 path([NexaTracePath])를 **저카디널리티 tag** `nexa.path`(`ignore`/`speak`)로 단다. IGNORE 는
 *    정책 추론 span 에서 끝나고(생성·전송 span 없음), SPEAK 는 generation·send span 까지 이어진다 — span 수·
 *    span 시간 합이 path 별로 달라 trace 에서 비용 차이가 드러난다.
 *  - span 이름은 고정 enum([NexaSpan])이고, tag 는 path/stage 같은 **저카디널리티 값만** 단다 — 원문·고카디널리티
 *    ID(user/channel/guild snowflake)는 span tag 로 절대 싣지 않는다(logging-boundary.md, T001 taxonomy).
 *
 * 순수성 경계: global/observability 인프라 컴포넌트. 도메인 타입을 import 하지 않고 원시값(span enum·path enum)만
 * 받는다 — 호출자가 결정을 원시값으로 풀어 넘긴다(BurstSegmentationMetrics 와 같은 경계 규칙).
 */
@Component
class NexaTracer(
    private val registry: ObservationRegistry,
) {
    /**
     * [span] 경계를 [path] 흐름으로 추적하며 [block] 을 실행한다. [block] 동안 span 이 열려 있어 하위 호출이
     * 자식 span 으로 묶인다(OTel context propagation). span 시간·자식 수가 trace 의 비용 신호다.
     */
    fun <T> span(
        span: NexaSpan,
        path: NexaTracePath,
        block: () -> T,
    ): T =
        Observation
            .createNotStarted(span.spanName, registry)
            .lowCardinalityKeyValue("nexa.path", path.label)
            .observe<T> { block() }!!
}

/**
 * NEXA trace span 이름(고정·저카디널리티). 주요 application 경계 — 호출자가 경계 진입 시 [NexaTracer.span] 으로 연다.
 */
enum class NexaSpan(
    val spanName: String,
) {
    /** 관찰 event 수집 경계. */
    INGEST("nexa.ingest"),

    /** 정책 추론 경계(IGNORE/SPEAK 양쪽 다 지난다 — 여기까지는 비용이 같다). */
    POLICY_INFERENCE("nexa.policy.inference"),

    /** 발화 generation 경계(SPEAK 에서만 — 외부 GLM 호출, IGNORE 는 안 들어온다). */
    GENERATION("nexa.generation"),

    /** Discord 전송 경계(SPEAK·실발화에서만 — shadow/IGNORE 는 안 들어온다). */
    DISCORD_SEND("nexa.discord.send"),
}

/**
 * 한 흐름의 결정 path(저카디널리티 trace tag). IGNORE 는 정책 추론에서 끝나고 SPEAK 는 generation·send 까지 간다 —
 * 이 tag 로 trace 에서 두 path 의 비용(span 수·시간)을 분리해 본다(acceptance T003).
 */
enum class NexaTracePath(
    val label: String,
) {
    /** 침묵 결정 — 생성·전송 span 없음(저비용). */
    IGNORE("ignore"),

    /** 발화 결정 — 생성·전송 span 까지 이어짐(고비용). */
    SPEAK("speak"),
}
