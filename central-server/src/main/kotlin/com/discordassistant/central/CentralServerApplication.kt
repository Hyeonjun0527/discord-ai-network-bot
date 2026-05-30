package com.discordassistant.central

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 커뮤니티 로컬 AI Provider Pool 중앙 서버 (ADR 0003/0004).
 *
 * 책임: Discord 처리(JDA) · Provider Pool · 라우팅 · Provider Session · 정책 ·
 * WebSocket 릴레이(에이전트 연결) · 사용량/기여 기록 · 헬스 모니터.
 *
 * Provider Agent(유저/프로바이더 PC)는 Python 으로 유지되며, 이 서버와 `specs/.../api.md §8`
 * 의 JSON WS 프로토콜로 통신한다.
 */
@SpringBootApplication
class CentralServerApplication

fun main(args: Array<String>) {
    runApplication<CentralServerApplication>(*args)
}
