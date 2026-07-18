package com.discordassistant.central.global.crypto

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldCryptoTest {
    @AfterEach
    fun resetKey() {
        FieldCrypto.configure(null)
    }

    @Test
    fun `키 설정 시 암호화-복호화 round-trip`() {
        FieldCrypto.configure("test-key-1234567890")
        val plain = "당신은 「니아」, 다정한 AI 멤버예요."
        val enc = FieldCrypto.encrypt(plain)!!
        assertTrue(enc.startsWith("enc1:"))
        assertNotEquals(plain, enc)
        assertEquals(plain, FieldCrypto.decrypt(enc))
    }

    @Test
    fun `레거시 평문은 접두사가 없으면 그대로 읽힌다`() {
        FieldCrypto.configure("k-abcdefg-1")
        assertEquals("평문 레거시", FieldCrypto.decrypt("평문 레거시"))
    }

    @Test
    fun `키 미설정이면 평문 통과(개발-테스트)`() {
        FieldCrypto.configure(null)
        assertEquals("x", FieldCrypto.encrypt("x"))
        assertEquals("x", FieldCrypto.decrypt("x"))
        FieldCrypto.configure("") // 빈 키도 비활성
        assertEquals("y", FieldCrypto.encrypt("y"))
    }

    @Test
    fun `null 은 null`() {
        FieldCrypto.configure("k-2")
        assertNull(FieldCrypto.encrypt(null))
        assertNull(FieldCrypto.decrypt(null))
    }

    @Test
    fun `암호문이 있는데 키가 사라지면 암호문을 평문으로 노출하지 않는다`() {
        FieldCrypto.configure("key-before-restart")
        val encrypted = FieldCrypto.encrypt("123456789012345678")!!

        FieldCrypto.configure(null)

        assertEquals(encrypted, FieldCrypto.decrypt(encrypted))
        assertNull(FieldCrypto.decryptOrNull(encrypted))
    }

    @Test
    fun `잘못된 키나 손상된 암호문은 null로 격리한다`() {
        FieldCrypto.configure("correct-key")
        val encrypted = FieldCrypto.encrypt("routing-value")!!

        FieldCrypto.configure("wrong-key")

        assertNull(FieldCrypto.decryptOrNull(encrypted))
        assertNull(FieldCrypto.decryptOrNull("enc1:not-valid-base64"))
    }
}
