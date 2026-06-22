package com.discordassistant.central.participation.application.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * NEXA-P18-T015 acceptance: active→previous 원자적 rollback, 그리고 in-flight decision 은 사용 model version 을
 * 유지하거나 취소하는 규칙이 있다(혼합 추론 금지).
 */
class ModelRollbackServiceTest {
    private val v1 = LiveModelArtifact(modelId = "m", modelVersion = "v1", artifactSha256 = "h1")
    private val v2 = LiveModelArtifact(modelId = "m", modelVersion = "v2", artifactSha256 = "h2")

    private fun service(initial: LiveModelPointer?): Pair<ModelRollbackService, MutableList<LiveModelPointer?>> {
        val state = mutableListOf<LiveModelPointer?>(initial)
        val svc = ModelRollbackService(loadPointer = { state.last() }, storePointer = { state.add(it) })
        return svc to state
    }

    @Test
    fun `rollback atomically switches active to previous`() {
        val (svc, state) = service(LiveModelPointer(active = v2, previous = v1))
        val newActive = svc.rollBack()

        assertEquals(v1, newActive)
        // 원자적: active 와 previous 가 통째로 새 인스턴스로 교체됐다(한 단계 rollback, 새 previous 없음).
        val pointer = state.last()!!
        assertEquals(v1, pointer.active)
        assertNull(pointer.previous)
    }

    @Test
    fun `rollback without previous fails`() {
        val (svc, _) = service(LiveModelPointer(active = v2, previous = null))
        assertThrows(ModelRollbackException::class.java) { svc.rollBack() }
    }

    @Test
    fun `promote preserves current active as rollback target`() {
        val (svc, _) = service(LiveModelPointer(active = v1, previous = null))
        val updated = svc.promote(v2)
        assertEquals(v2, updated.active)
        assertEquals(v1, updated.previous) // v1 이 다음 rollback 대상으로 보존.
    }

    @Test
    fun `in-flight decision keeps model version when active unchanged`() {
        val (svc, _) = service(LiveModelPointer(active = v2, previous = v1))
        // 결정 시작 시 v2 를 봤고 active 가 여전히 v2 → 유지.
        assertEquals(InFlightResolution.KEEP, svc.resolveInFlight("v2"))
    }

    @Test
    fun `in-flight decision cancels when rollback changed active mid-flight`() {
        val (svc, _) = service(LiveModelPointer(active = v2, previous = v1))
        // 결정은 v2 로 시작했는데 rollback 후 active 가 v1 이 됨 → 그 결정은 취소(혼합 추론 금지).
        svc.rollBack()
        assertEquals(InFlightResolution.CANCEL, svc.resolveInFlight("v2"))
        // rollback 후 새로 v1 으로 시작한 결정은 유지된다.
        assertEquals(InFlightResolution.KEEP, svc.resolveInFlight("v1"))
    }

    @Test
    fun `in-flight decision cancels when no live pointer`() {
        val (svc, _) = service(null)
        assertEquals(InFlightResolution.CANCEL, svc.resolveInFlight("v1"))
    }
}
