package com.discordassistant.central.global.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * NEXA 채팅 점유율 metric(NEXA-P18-T005). 최근 창(5분/1시간)에서 human burst 대비 NEXA burst share 와 token/char
 * share 를 **집계 gauge** 로 노출한다. 호출자(participation/share 집계 잡)가 창별 카운트를 풀어 넘기면
 * gauge 로 게시한다.
 *
 * **acceptance(T005) — 메시지 조각 수가 아닌 burst 수와 token/char share 를 함께 본다**:
 *  - share 는 **burst 수** 기준(`nexa_chat_share_burst_ratio`)과 **token/char** 기준(`nexa_chat_share_token_ratio`)을
 *    함께 게시한다 — 조각(fragment) 수가 아니다(짧게 여러 조각으로 쪼개도 burst 1 로 센다).
 *  - window 는 저카디널리티 label(`5m`/`1h`)뿐 — guild/channel/user ID 를 label 로 노출하지 않는다.
 *
 * gauge 는 마지막 게시값을 들고 있다(AtomicReference) — 집계 잡이 주기적으로 갱신한다.
 */
@Component
class NexaChatShareMetrics(
    private val meter: MeterRegistry,
) {
    private val burstRatio: Map<ShareWindow, AtomicReference<Double>> = registerGauges("nexa_chat_share_burst_ratio")
    private val tokenRatio: Map<ShareWindow, AtomicReference<Double>> = registerGauges("nexa_chat_share_token_ratio")

    private fun registerGauges(name: String): Map<ShareWindow, AtomicReference<Double>> =
        ShareWindow.entries.associateWith { window ->
            AtomicReference(0.0).also { holder ->
                meter.gauge(
                    name,
                    listOf(
                        io.micrometer.core.instrument.Tag
                            .of("window", window.label),
                    ),
                    holder,
                ) { it.get() }
            }
        }

    /**
     * 한 창의 점유율을 게시한다. [humanBursts]·[nexaBursts] 는 그 창의 burst 수, [humanTokens]·[nexaTokens] 는
     * token/char 합이다. 분모가 0(인간+NEXA 둘 다 0)이면 share 는 0(미정의 — 단정 금지).
     */
    fun publish(
        window: ShareWindow,
        humanBursts: Long,
        nexaBursts: Long,
        humanTokens: Long,
        nexaTokens: Long,
    ) {
        require(humanBursts >= 0 && nexaBursts >= 0 && humanTokens >= 0 && nexaTokens >= 0) {
            "share 카운트는 음수일 수 없다"
        }
        burstRatio.getValue(window).set(ratio(nexaBursts, humanBursts + nexaBursts))
        tokenRatio.getValue(window).set(ratio(nexaTokens, humanTokens + nexaTokens))
    }

    /** 마지막으로 게시된 burst share(테스트·내부 조회용). */
    fun burstShare(window: ShareWindow): Double = burstRatio.getValue(window).get()

    /** 마지막으로 게시된 token/char share(테스트·내부 조회용). */
    fun tokenShare(window: ShareWindow): Double = tokenRatio.getValue(window).get()

    private fun ratio(
        part: Long,
        total: Long,
    ): Double = if (total <= 0L) 0.0 else part.toDouble() / total.toDouble()
}

/** 점유율 집계 창(저카디널리티 label enum). */
enum class ShareWindow(
    val label: String,
) {
    FIVE_MIN("5m"),
    ONE_HOUR("1h"),
}
