package com.discordassistant.central.speech.application.port.out

import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/** Speech가 사람 말투 카드만 검색하는 포트. Judge/participation 패키지는 이 포트를 알지 못한다. */
fun interface HumanSpeechStyleRagPort {
    fun retrieve(packet: SpeechScenePacket): HumanSpeechStyleSelection

    object Noop : HumanSpeechStyleRagPort {
        override fun retrieve(packet: SpeechScenePacket): HumanSpeechStyleSelection = HumanSpeechStyleSelection.EMPTY
    }
}

/** 암호화된 사람 말투 카드 저장소 포트. 목록 조회와 명시적 전체 교체만 허용한다. */
interface HumanSpeechStyleExampleStorePort {
    fun listEnabled(): List<HumanSpeechStyleExample>

    fun listEnabled(responseMode: HumanSpeechResponseMode): List<HumanSpeechStyleExample> =
        listEnabled().filter { it.responseMode == responseMode }

    fun replaceAll(examples: List<HumanSpeechStyleExample>): Int
}

/** OpenAI embedding 호출을 Speech application에서 분리한다. 실패는 null로 표현해 발화 생성이 계속되게 한다. */
fun interface SpeechStyleEmbeddingPort {
    fun embedAll(texts: List<String>): List<FloatArray>?

    object Noop : SpeechStyleEmbeddingPort {
        override fun embedAll(texts: List<String>): List<FloatArray>? = null
    }
}
