package com.discordassistant.central.speech.application.port.out

/**
 * speech 발화 생성의 **사용량 기록** 아웃바운드 포트(NEXA-P14-T002, provider-neutral).
 *
 * anti-corruption adapter 가 클라우드 모델 호출 후 token 사용량을 central 관측에 남기기 위한 좁은 포트다 — speech
 * 가 routing 의 provider-pool 전용 UsageRecorder(providerId 등)를 직접 알지 않고도 requestlog/quota 연동을 유지한다
 * (acceptance T002). 기본 [Noop] 은 아무것도 하지 않아 단위 테스트·연동 미구성 환경을 막지 않는다(fire-and-forget).
 */
interface SpeechUsageRecorderPort {
    /** [guildId] 의 발화 생성 1건 token 사용량을 기록한다(실패는 흡수 — 관측 실패가 발화를 막지 않는다). */
    fun recordSpeechGeneration(
        guildId: Long,
        promptTokens: Int,
        completionTokens: Int,
        modelMetadata: String,
    )

    /** no-op 기본 구현(연동 미구성·테스트). */
    object Noop : SpeechUsageRecorderPort {
        override fun recordSpeechGeneration(
            guildId: Long,
            promptTokens: Int,
            completionTokens: Int,
            modelMetadata: String,
        ) {
        }
    }
}
