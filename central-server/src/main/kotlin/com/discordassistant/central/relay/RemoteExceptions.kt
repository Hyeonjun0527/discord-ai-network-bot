package com.discordassistant.central.relay

import com.discordassistant.central.relay.protocol.ErrorCode

/** 원격 추론 경로의 기본 예외. */
sealed class RemoteException(message: String) : RuntimeException(message) {
    abstract val code: String
}

/** 대기 큐가 가득 차 요청을 받을 수 없음. */
class AgentBusyException(message: String = "에이전트가 바쁩니다") : RemoteException(message) {
    override val code = ErrorCode.BUSY
}

/** 원격 에이전트 응답 타임아웃. */
class RemoteTimeoutException(message: String = "원격 에이전트 응답 시간 초과") : RemoteException(message) {
    override val code = ErrorCode.TIMEOUT
}

/** 에이전트가 error 프레임으로 보고한 실패. */
class RemoteInferException(override val code: String, message: String) : RemoteException(message)

/** 대기 중 연결이 끊김. */
class ConnectionClosedException(message: String = "연결이 끊겼습니다") : RemoteException(message) {
    override val code = ErrorCode.OFFLINE
}
