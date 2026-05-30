package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.Frame

/**
 * 에이전트 연결 추상(보내기/닫기). Spring WebSocket 세션을 감싸는 구현(차수 3)과
 * 테스트용 가짜가 이 인터페이스를 구현한다. 수신(read loop)은 핸들러가 담당한다.
 */
interface AgentConnection {
    /** 로깅/진단용 식별자(토큰 미포함). */
    val remoteId: String

    /** 프레임을 인코딩해 전송한다. 실패 시 예외. */
    fun sendFrame(frame: Frame)

    /** 연결을 닫는다. */
    fun close(reason: String = "")
}
