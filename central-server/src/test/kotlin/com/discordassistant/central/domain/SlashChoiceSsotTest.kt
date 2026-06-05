package com.discordassistant.central.domain

import com.discordassistant.central.provider.domain.model.ProviderModelScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 슬래시 옵션 choice 의 SSOT 가 도메인 enum 임을 고정한다. 카탈로그(`SlashCommandCatalog`)는 이 목록을
 * 그대로 부착하므로, 값 집합/순서가 바뀌면 여기서 먼저 깨져 드리프트를 막는다.
 */
class SlashChoiceSsotTest {
    @Test
    fun `응답 모드 choice — fast balanced deep saving`() {
        assertEquals(
            listOf("빠른 답변" to "fast", "균형 모드" to "balanced", "깊은 답변" to "deep", "절약 모드" to "saving"),
            ResponseMode.slashChoices(),
        )
    }

    @Test
    fun `역할 정책 모델 수준 choice — RESTRICTED 제외 3종, 값은 enum 이름`() {
        assertEquals(
            listOf("LIGHT (가벼움)" to "LIGHT", "STANDARD (표준)" to "STANDARD", "HEAVY (무거움)" to "HEAVY"),
            ModelBurden.rolePolicyChoices(),
        )
    }

    @Test
    fun `신고 검수 결정 choice — dismiss suspend remove`() {
        assertEquals(
            listOf("신고 기각" to "dismiss", "일시 중단" to "suspend", "제거" to "remove"),
            PresetReportStatus.decisionChoices(),
        )
    }

    @Test
    fun `색인 완료 결과 choice — QUEUED 제외 종단 3종`() {
        assertEquals(
            listOf("완료" to "completed", "실패" to "failed", "취소" to "cancelled"),
            EmbeddingJobStatus.completionChoices(),
        )
    }

    @Test
    fun `프로바이더 허용범위 choice + 정규화`() {
        assertEquals(listOf("모두" to "all", "신뢰 역할" to "trusted", "관리자만" to "admin"), ProviderModelScope.slashChoices())
        assertEquals(ProviderModelScope.TRUSTED, ProviderModelScope.fromWire("trusted"))
        assertEquals(ProviderModelScope.ALL, ProviderModelScope.fromWire("garbage")) // 견고한 폴백
        assertEquals(ProviderModelScope.ALL, ProviderModelScope.fromWire(null))
    }

    @Test
    fun `다중응답 모드 choice + 정규화`() {
        assertEquals(listOf("단일 답변" to "single", "후보 비교" to "compare", "관점 비교" to "debate"), MultiResponseMode.slashChoices())
        assertEquals(MultiResponseMode.DEBATE, MultiResponseMode.fromWire("DEBATE"))
        assertEquals(MultiResponseMode.SINGLE, MultiResponseMode.fromWire("")) // 기존 ifBlank{single} 동작
        assertEquals(MultiResponseMode.SINGLE, MultiResponseMode.fromWire("unknown"))
    }
}
