package com.discordassistant.central.participation.domain.model.action

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P08-T002 SocialAct taxonomy 의 acceptance 단위 테스트. */
class SocialActTest {
    @Test
    fun `acceptance — 안정 코드를 가지며 핵심 act 가 모두 존재한다`() {
        val names = SocialAct.entries.map { it.wireName }.toSet()
        assertThat(names).contains(
            "acknowledge",
            "agree",
            "disagree",
            "tease",
            "ask",
            "correct",
            "self_disclose",
            "change_topic",
        )
    }

    @Test
    fun `acceptance — 미지 라벨은 자유 텍스트 보존 없이 UNKNOWN 으로 정규화된다`() {
        assertThat(SocialAct.fromWireName("totally_made_up_label")).isEqualTo(SocialAct.UNKNOWN)
        assertThat(SocialAct.fromWireName("").isUnknown).isTrue()
    }

    @Test
    fun `알려진 wireName 은 정확히 복원된다`() {
        assertThat(SocialAct.fromWireName("tease")).isEqualTo(SocialAct.TEASE)
        assertThat(SocialAct.fromWireName("change_topic")).isEqualTo(SocialAct.CHANGE_TOPIC)
    }

    @Test
    fun `wireName 은 enum 별로 유일하다`() {
        val wireNames = SocialAct.entries.map { it.wireName }
        assertThat(wireNames).doesNotHaveDuplicates()
    }

    @Test
    fun `카탈로그 버전이 정의된다`() {
        assertThat(SocialAct.CATALOG_VERSION).isGreaterThanOrEqualTo(1)
    }
}
