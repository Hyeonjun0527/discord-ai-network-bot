package com.discordassistant.central.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaPromptTemplateTest {
    @Test
    fun `끝의 빈 동적 값은 현재 렌더링 전체를 고정 prefix로 쓸 수 있다`() {
        val template = "고정 규칙\n{{dynamic}}"
        val values = mapOf("dynamic" to "")

        assertThat(NiaPromptTemplate.render(template, values)).isEqualTo("고정 규칙")
        assertThat(NiaPromptTemplate.stablePrefixChars(template, values, emptyMap()))
            .isEqualTo("고정 규칙".length)
    }

    @Test
    fun `앞의 빈 동적 값은 뒤 문구를 고정 prefix로 오인하지 않는다`() {
        val template = "{{dynamic}}\n고정 규칙"
        val values = mapOf("dynamic" to "")

        assertThat(NiaPromptTemplate.stablePrefixChars(template, values, emptyMap())).isZero()
    }

    @Test
    fun `반복된 동적 placeholder는 첫 위치에서 cache를 끊는다`() {
        val template = "고정:{{dynamic}}:중간:{{dynamic}}"
        val values = mapOf("dynamic" to "값")

        assertThat(NiaPromptTemplate.stablePrefixChars(template, values, emptyMap()))
            .isEqualTo("고정:".length)
    }

    @Test
    fun `부분 고정 값 뒤부터 동적으로 계산한다`() {
        val template = "앞\n{{mixed}}\n뒤"
        val values = mapOf("mixed" to "고정동적")

        assertThat(
            NiaPromptTemplate.stablePrefixChars(
                template,
                values,
                stableValueChars = mapOf("mixed" to "고정".length),
            ),
        ).isEqualTo("앞\n고정".length)
    }
}
