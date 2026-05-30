package com.discordassistant.central.alert

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Discord 웹훅 알림(차수 15 #231). `central.alert.discord-webhook` 가 설정된 경우에만 빈으로 등록되어
 * @Primary 로 LoggingNotifier 를 대체한다. 미설정(기본)이면 등록되지 않아 LoggingNotifier 로 폴백.
 */
@Component
@Primary
@ConditionalOnProperty("central.alert.discord-webhook")
class DiscordWebhookNotifier(
    @param:Value("\${central.alert.discord-webhook}") private val webhookUrl: String,
) : Notifier {
    private val log = LoggerFactory.getLogger(DiscordWebhookNotifier::class.java)
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun notify(severity: Severity, title: String, message: String) {
        val emoji = when (severity) {
            Severity.CRITICAL -> "🔴"
            Severity.WARN -> "🟡"
            Severity.INFO -> "🔵"
        }
        val body = mapper.writeValueAsString(mapOf("content" to "$emoji **[$severity] $title**\n$message"))
        try {
            val req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = client.send(req, HttpResponse.BodyHandlers.discarding())
            if (res.statusCode() !in 200..299) {
                log.warn("Discord 웹훅 응답 비정상: {}", res.statusCode())
            }
        } catch (e: Exception) {
            // 알림 실패가 본 기능을 막지 않도록 흡수(로그만).
            log.warn("Discord 웹훅 전송 실패: {}", e.message)
        }
    }
}
