package com.discordassistant.central.global.crypto

import com.discordassistant.central.global.crypto.ScopedPseudonymizer.Purpose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScopedPseudonymizerTest {
    private val guildA = 111_111_111_111_111_111L
    private val guildB = 222_222_222_222_222_222L
    private val userId = 999_888_777_666_555_444L

    @BeforeEach
    fun setUp() {
        ScopedPseudonymizer.configure(mapOf(1 to "key-v1-secret"), active = 1)
    }

    @Test
    fun `결정적 — 같은 입력은 항상 같은 가명`() {
        val a = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId)
        val b = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId)
        assertEquals(a, b)
    }

    @Test
    fun `길드 격리 — 같은 snowflake 라도 guildId 가 다르면 다른 가명(cross-guild linkage 차단)`() {
        val inA = ScopedPseudonymizer.pseudonymize(Purpose.MEMORY, guildA, userId)
        val inB = ScopedPseudonymizer.pseudonymize(Purpose.MEMORY, guildB, userId)
        assertNotEquals(inA, inB)
    }

    @Test
    fun `용도 분리 — 같은 snowflake-guildId 라도 purpose 가 다르면 다른 가명`() {
        val forLog = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId)
        val forMemory = ScopedPseudonymizer.pseudonymize(Purpose.MEMORY, guildA, userId)
        val forTraining = ScopedPseudonymizer.pseudonymize(Purpose.TRAINING, guildA, userId)
        assertNotEquals(forLog, forMemory)
        assertNotEquals(forLog, forTraining)
        assertNotEquals(forMemory, forTraining)
    }

    @Test
    fun `역추적 불가 — 가명은 원본 snowflake 를 평문으로 포함하지 않는다`() {
        val pseudonym = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId)
        assertFalse(pseudonym.contains(userId.toString()))
        assertFalse(pseudonym.contains(guildA.toString()))
    }

    @Test
    fun `키 회전 — 키 버전이 다르면 다른 가명이고 접두사로 버전 식별`() {
        ScopedPseudonymizer.configure(mapOf(1 to "key-v1-secret", 2 to "key-v2-secret"), active = 2)
        val v1 = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId, keyVersion = 1)
        val v2 = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId, keyVersion = 2)
        assertNotEquals(v1, v2)
        assertEquals("v1", v1.substringBefore(":"))
        assertEquals("v2", v2.substringBefore(":"))
        // active=2 이므로 버전 미지정 시 활성 버전(2)을 쓴다.
        assertEquals(v2, ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId))
    }

    @Test
    fun `키 미설정 폴백도 결정적이고 스코프 격리를 유지`() {
        ScopedPseudonymizer.configure(emptyMap(), active = 1)
        val a = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId)
        val b = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildA, userId)
        val other = ScopedPseudonymizer.pseudonymize(Purpose.LOG, guildB, userId)
        assertEquals(a, b)
        assertNotEquals(a, other)
    }
}
