package com.discordassistant.central.provider

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 경량 감사 로그 (K-차수 4). 등록/승인/제거 등 관리 행위를 기록한다. 영속화(JPA)는 K-차수 6.
 * 토큰 등 비밀 값은 기록하지 않는다.
 */
@Component
class AuditLog {
    data class Entry(val action: String, val actor: String, val target: String, val detail: String)

    private val log = LoggerFactory.getLogger(AuditLog::class.java)
    private val entries = CopyOnWriteArrayList<Entry>()

    fun record(action: String, actor: String, target: String, detail: String = "") {
        entries.add(Entry(action, actor, target, detail))
        log.info("AUDIT action={} actor={} target={} {}", action, actor, target, detail)
    }

    fun all(): List<Entry> = entries.toList()
}
