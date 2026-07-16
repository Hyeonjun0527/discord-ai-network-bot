package com.discordassistant.central.speech.adapter.outbound.routing

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 발화 생성 모델 ID·endpoint·budget 설정 외부화(NEXA-P14-T003, adapter/outbound/routing).
 *
 * GLM Air 를 기본 후보로 두되 모델 ID·endpoint(타임아웃·재시도)를 **환경 설정**으로 분리한다.
 *
 * **acceptance(T003) — 코드 교체 없이 모델 업데이트·rollback 이 가능하다**: 모델 라벨([model])과 호출 budget 은
 * `application.yml` 의 `central.speech.*`로 주입된다. 모델 미설정 시에는 `/질문` 무료 클라우드 폴백과 같은 빠른
 * 기본값([DEFAULT_SPEECH_MODEL])을 쓴다. 운영 모델 교체는 `NEXA_SPEECH_MODEL` 환경변수로 한다. (이 클래스는
 * *Config 라 커버리지 집계 제외 — 설정 글루.)
 *
 * endpoint(base-url)·api-key 는 routing 의 CloudLlm 구현(`central.cloud.zai-*`)이 SSOT 로 소유한다 — speech 는
 * 모델 라벨·budget 만 외부화하고 HTTP/endpoint 를 중복 소유하지 않는다(anti-corruption: speech 는 endpoint 를 모름).
 */
@Component
class SpeechModelConfig(
    @param:Value("\${central.speech.model:glm-4.5-air}") val model: String,
    @param:Value("\${central.speech.timeout-seconds:8}") private val timeoutSeconds: Long,
    @param:Value("\${central.speech.max-retries:1}") val maxRetries: Int,
    // 발화 샘플링 randomness. 후보 2개 안에서 다양성을 확보하되 페르소나 이탈은 줄이는 보수적 기본값이다.
    @param:Value("\${central.speech.temperature:0.5}") val temperature: Double,
) {
    companion object {
        const val DEFAULT_SPEECH_MODEL = "glm-4.5-air"
    }

    /** 1회 호출 timeout. */
    val perCallTimeout: Duration
        get() = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))
}
