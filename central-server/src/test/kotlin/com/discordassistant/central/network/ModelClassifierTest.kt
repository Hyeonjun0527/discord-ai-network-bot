package com.discordassistant.central.network

import com.discordassistant.central.domain.ModelBurden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelClassifierTest {
    @Test
    fun `소형 모델은 패밀리와 무관하게 LIGHT`() {
        // qwen 통째로 STANDARD 로 못박지 않는다 — 0.8B 는 LIGHT.
        assertEquals(ModelBurden.LIGHT, ModelClassifier.maxBurden(listOf("qwen3.5:0.8b")))
        assertEquals(ModelBurden.LIGHT, ModelClassifier.maxBurden(listOf("llama3.1:8b")))
        assertEquals(ModelBurden.LIGHT, ModelClassifier.maxBurden(listOf("gemma4:4b")))
        assertEquals(ModelBurden.LIGHT, ModelClassifier.maxBurden(listOf("qwen2.5:1.5b")))
    }

    @Test
    fun `중형은 STANDARD, 대형은 HEAVY`() {
        assertEquals(ModelBurden.STANDARD, ModelClassifier.maxBurden(listOf("gemma4:12b")))
        assertEquals(ModelBurden.STANDARD, ModelClassifier.maxBurden(listOf("qwen2.5-coder:7b")))
        assertEquals(ModelBurden.HEAVY, ModelClassifier.maxBurden(listOf("gemma4:27b")))
        assertEquals(ModelBurden.HEAVY, ModelClassifier.maxBurden(listOf("qwen2.5:32b")))
        assertEquals(ModelBurden.HEAVY, ModelClassifier.maxBurden(listOf("llama3.1:70b")))
    }

    @Test
    fun `비전(멀티모달) 모델은 vision 태그`() {
        assertTrue(ModelClassifier.capabilityTags(listOf("gemma4:4b")).contains("vision"))
        assertTrue(ModelClassifier.capabilityTags(listOf("gemma3:12b")).contains("vision"))
        assertTrue(ModelClassifier.capabilityTags(listOf("llava:7b")).contains("vision"))
        assertFalse(ModelClassifier.capabilityTags(listOf("llama3.1:8b")).contains("vision"))
    }

    @Test
    fun `코딩 모델은 coding 태그, 빈 목록은 빈 태그`() {
        assertTrue(ModelClassifier.capabilityTags(listOf("qwen2.5-coder:7b")).contains("coding"))
        assertTrue(ModelClassifier.capabilityTags(listOf("llama3.1:8b")).contains("local-llm"))
        assertTrue(ModelClassifier.capabilityTags(emptyList()).isEmpty())
    }
}
