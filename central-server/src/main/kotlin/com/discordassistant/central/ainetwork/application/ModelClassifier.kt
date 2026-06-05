package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.domain.ModelBurden

/**
 * 모델 이름 기반 분류(순수 함수, 테스트 가능). 풀은 **모델 무관**이지만, 라우팅·공정성을 위해
 * 이름 패턴/파라미터 크기로 capability 태그와 처리 부담(burden)을 추정한다.
 *
 * burden 은 **크기 우선**이다. 같은 패밀리라도 0.8B 와 32B 는 부담이 전혀 다르므로,
 * 패밀리명("qwen" 등)만으로 STANDARD 로 못박지 않는다(예: qwen3.5:0.8b → LIGHT).
 */
object ModelClassifier {
    fun capabilityTags(modelNames: List<String>): List<String> {
        val joined = modelNames.joinToString(" ").lowercase()
        return buildSet {
            if (listOf("code", "coder", "deepseek", "qwen").any { it in joined }) add("coding")
            if (listOf("translate", "nllb", "aya").any { it in joined }) add("translation")
            if (listOf("32b", "70b", "long", "large").any { it in joined }) add("long-context")
            // 비전(멀티모달) — Gemma 3/4·LLaVA·Llama vision·Qwen-VL·MiniCPM-V 등(이미지 이해).
            if (
                listOf("vision", "llava", "llama3.2-vision", "gemma3", "gemma4", "minicpm-v", "moondream", "qwen2-vl", "qwen2.5-vl")
                    .any { it in joined }
            ) {
                add("vision")
            }
            if (modelNames.isNotEmpty()) add("local-llm")
        }.toList()
    }

    fun maxBurden(modelNames: List<String>): ModelBurden {
        val joined = modelNames.joinToString(" ").lowercase()
        return when {
            // 초대형·MoE → HEAVY
            listOf("70b", "65b", "72b", "405b", "large", "mixtral").any { it in joined } -> ModelBurden.HEAVY
            // 대형(≥27B) → HEAVY. Gemma 27B 포함.
            listOf("27b", "30b", "32b", "34b").any { it in joined } -> ModelBurden.HEAVY
            // 중형(9~14B)·코드 모델 → STANDARD. Gemma 9B/12B 포함.
            listOf("9b", "12b", "13b", "14b", "coder", "deepseek").any { it in joined } -> ModelBurden.STANDARD
            // 그 외(소형 ≤8B: 0.5b/0.8b/1b/1.5b/2b/3b/4b/7b/8b 등) → LIGHT
            else -> ModelBurden.LIGHT
        }
    }
}
