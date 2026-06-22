package com.discordassistant.central.arch.nexafixture.nexaadapter

/**
 * T023 self-test fixture — NEXA 신규 도메인의 adapter 내부 구현(공개 API 아님).
 * 기존 도메인은 이 클래스를 직접 import 해서는 안 되고, 공개 application port 만 소비해야 한다.
 */
class FakeSpeechPersistenceAdapter {
    fun internalState(): String = "internal"
}
