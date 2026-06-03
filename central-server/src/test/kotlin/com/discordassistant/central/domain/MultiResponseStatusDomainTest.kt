package com.discordassistant.central.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 다중응답 status 도메인 enum(run/candidate/synthesis) 단위 테스트.
 *
 * 와이어 값 보존(JSON/DB 불변), 견고 파싱 폴백, 실제 코드 전이에서 도출한 전이가드, 의미 헬퍼를 검증한다.
 */
class MultiResponseStatusDomainTest {
    @Test
    fun `run status wire 값은 소문자 문자열로 기존 JSON DB 와 동일하다`() {
        assertEquals("created", MultiResponseRunStatus.CREATED.wire)
        assertEquals("planned", MultiResponseRunStatus.PLANNED.wire)
        assertEquals("running", MultiResponseRunStatus.RUNNING.wire)
        assertEquals("no_provider", MultiResponseRunStatus.NO_PROVIDER.wire)
        assertEquals("blocked_sensitive", MultiResponseRunStatus.BLOCKED_SENSITIVE.wire)
        assertEquals("disabled_by_policy", MultiResponseRunStatus.DISABLED_BY_POLICY.wire)
        assertEquals("completed", MultiResponseRunStatus.COMPLETED.wire)
        assertEquals("failed", MultiResponseRunStatus.FAILED.wire)
    }

    @Test
    fun `run status fromWire 는 대소문자 흡수하고 알 수 없는 값은 CREATED 로 폴백한다`() {
        assertEquals(MultiResponseRunStatus.RUNNING, MultiResponseRunStatus.fromWire(" RUNNING "))
        assertEquals(MultiResponseRunStatus.COMPLETED, MultiResponseRunStatus.fromWire("completed"))
        assertEquals(MultiResponseRunStatus.CREATED, MultiResponseRunStatus.fromWire(null))
        assertEquals(MultiResponseRunStatus.CREATED, MultiResponseRunStatus.fromWire("__broken__"))
    }

    @Test
    fun `run status 전이가드는 실제 코드 전이를 허용하고 종단 상태에서 나가는 전이를 막는다`() {
        // 실제 흐름: planned → {running, no_provider, blocked_sensitive, disabled_by_policy, completed, failed}
        assertTrue(MultiResponseRunStatus.PLANNED.canTransitionTo(MultiResponseRunStatus.RUNNING))
        assertTrue(MultiResponseRunStatus.PLANNED.canTransitionTo(MultiResponseRunStatus.NO_PROVIDER))
        assertTrue(MultiResponseRunStatus.PLANNED.canTransitionTo(MultiResponseRunStatus.BLOCKED_SENSITIVE))
        assertTrue(MultiResponseRunStatus.PLANNED.canTransitionTo(MultiResponseRunStatus.DISABLED_BY_POLICY))
        assertTrue(MultiResponseRunStatus.RUNNING.canTransitionTo(MultiResponseRunStatus.COMPLETED))
        assertTrue(MultiResponseRunStatus.RUNNING.canTransitionTo(MultiResponseRunStatus.FAILED))
        assertTrue(MultiResponseRunStatus.RUNNING.canTransitionTo(MultiResponseRunStatus.NO_PROVIDER))
        assertTrue(MultiResponseRunStatus.CREATED.canTransitionTo(MultiResponseRunStatus.PLANNED))
        // 동일 상태 재대입(idempotent) 허용
        assertTrue(MultiResponseRunStatus.COMPLETED.canTransitionTo(MultiResponseRunStatus.COMPLETED))
        // 종단 상태에서 다른 상태로의 전이는 없음
        assertFalse(MultiResponseRunStatus.COMPLETED.canTransitionTo(MultiResponseRunStatus.RUNNING))
        assertFalse(MultiResponseRunStatus.FAILED.canTransitionTo(MultiResponseRunStatus.COMPLETED))
        assertFalse(MultiResponseRunStatus.RUNNING.canTransitionTo(MultiResponseRunStatus.PLANNED))
    }

    @Test
    fun `run status 의미 헬퍼`() {
        assertTrue(MultiResponseRunStatus.COMPLETED.isCompleted)
        assertFalse(MultiResponseRunStatus.RUNNING.isCompleted)
        assertTrue(MultiResponseRunStatus.COMPLETED.isTerminal)
        assertTrue(MultiResponseRunStatus.NO_PROVIDER.isTerminal)
        assertTrue(MultiResponseRunStatus.BLOCKED_SENSITIVE.isTerminal)
        assertTrue(MultiResponseRunStatus.DISABLED_BY_POLICY.isTerminal)
        assertTrue(MultiResponseRunStatus.FAILED.isTerminal)
        assertFalse(MultiResponseRunStatus.CREATED.isTerminal)
        assertFalse(MultiResponseRunStatus.PLANNED.isTerminal)
        assertFalse(MultiResponseRunStatus.RUNNING.isTerminal)
    }

    @Test
    fun `candidate status wire 값과 fromWire 폴백`() {
        assertEquals("pending", CandidateStatus.PENDING.wire)
        assertEquals("planned", CandidateStatus.PLANNED.wire)
        assertEquals("completed", CandidateStatus.COMPLETED.wire)
        assertEquals("failed", CandidateStatus.FAILED.wire)
        assertEquals("timeout", CandidateStatus.TIMEOUT.wire)
        assertEquals("rejected", CandidateStatus.REJECTED.wire)
        assertEquals(CandidateStatus.TIMEOUT, CandidateStatus.fromWire("TIMEOUT"))
        assertEquals(CandidateStatus.COMPLETED, CandidateStatus.fromWire(" completed "))
        assertEquals(CandidateStatus.PENDING, CandidateStatus.fromWire(null))
        assertEquals(CandidateStatus.PENDING, CandidateStatus.fromWire("unknown"))
    }

    @Test
    fun `candidate status 전이가드와 헬퍼`() {
        assertTrue(CandidateStatus.PENDING.canTransitionTo(CandidateStatus.PLANNED))
        assertTrue(CandidateStatus.PLANNED.canTransitionTo(CandidateStatus.COMPLETED))
        assertTrue(CandidateStatus.PLANNED.canTransitionTo(CandidateStatus.FAILED))
        assertTrue(CandidateStatus.PLANNED.canTransitionTo(CandidateStatus.TIMEOUT))
        assertTrue(CandidateStatus.PLANNED.canTransitionTo(CandidateStatus.REJECTED))
        assertTrue(CandidateStatus.COMPLETED.canTransitionTo(CandidateStatus.COMPLETED))
        assertFalse(CandidateStatus.COMPLETED.canTransitionTo(CandidateStatus.FAILED))
        assertTrue(CandidateStatus.COMPLETED.isCompleted)
        assertFalse(CandidateStatus.PENDING.isCompleted)
        assertTrue(CandidateStatus.COMPLETED.isTerminal)
        assertTrue(CandidateStatus.TIMEOUT.isTerminal)
        assertFalse(CandidateStatus.PENDING.isTerminal)
        assertFalse(CandidateStatus.PLANNED.isTerminal)
    }

    @Test
    fun `synthesis status wire fromWire 전이가드 헬퍼`() {
        assertEquals("pending", SynthesisStatus.PENDING.wire)
        assertEquals("completed", SynthesisStatus.COMPLETED.wire)
        assertEquals(SynthesisStatus.COMPLETED, SynthesisStatus.fromWire("COMPLETED"))
        assertEquals(SynthesisStatus.PENDING, SynthesisStatus.fromWire(null))
        assertEquals(SynthesisStatus.PENDING, SynthesisStatus.fromWire("nope"))
        assertTrue(SynthesisStatus.PENDING.canTransitionTo(SynthesisStatus.COMPLETED))
        assertTrue(SynthesisStatus.COMPLETED.canTransitionTo(SynthesisStatus.COMPLETED))
        assertFalse(SynthesisStatus.COMPLETED.canTransitionTo(SynthesisStatus.PENDING))
        assertTrue(SynthesisStatus.COMPLETED.isTerminal)
        assertTrue(SynthesisStatus.COMPLETED.isCompleted)
        assertFalse(SynthesisStatus.PENDING.isTerminal)
        assertFalse(SynthesisStatus.PENDING.isCompleted)
    }
}
