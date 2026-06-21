package com.discordassistant.central.arch.nexafixture.downstreamadapter

/**
 * 금지 의존 #2 self-test fixture — speech/actionruntime 의 adapter 내부 구현(공개 application port 아님)
 * 및 JDA 전송 구현 역할의 표식 클래스.
 *
 * participation 소스는 이 클래스(어댑터 내부 구현)를 직접 import 해서는 안 되고,
 * speech/actionruntime 의 공개 application port 만 호출해야 한다.
 */
class FakeSpeechActionAdapter {
    fun sendViaJda(): String = "internal-transport"
}
