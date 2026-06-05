package com.discordassistant.central.ainetwork.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 알림 심각도(차수 15 #220). */
enum class Severity { INFO, WARN, CRITICAL }

/** 운영 알림 전송 추상화(차수 15 #220/#231). 구현은 로그/Discord 웹훅 등. */
interface Notifier {
    fun notify(
        severity: Severity,
        title: String,
        message: String,
    )
}

/**
 * 기본 알림: 로그로만 남긴다(외부 웹훅 미설정 시 안전한 no-op 대체).
 * 웹훅이 설정되면 DiscordWebhookNotifier(@Primary)가 우선한다.
 */
@Component
class LoggingNotifier : Notifier {
    private val log = LoggerFactory.getLogger(LoggingNotifier::class.java)

    override fun notify(
        severity: Severity,
        title: String,
        message: String,
    ) {
        when (severity) {
            Severity.CRITICAL -> log.error("[ALERT/{}] {} — {}", severity, title, message)
            Severity.WARN -> log.warn("[ALERT/{}] {} — {}", severity, title, message)
            Severity.INFO -> log.info("[ALERT/{}] {} — {}", severity, title, message)
        }
    }
}
