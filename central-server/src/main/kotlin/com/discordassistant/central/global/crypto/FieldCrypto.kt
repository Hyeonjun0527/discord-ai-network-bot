package com.discordassistant.central.global.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 필드 단위 at-rest 암호화(안전정책 B5). 보관 데이터(시스템 프롬프트·전역 프롬프트·RAG 프리뷰)에 적용한다.
 *
 * - 키: `NEXA_FIELD_ENC_KEY`(env). **미설정이면 평문**(개발/테스트). 운영은 반드시 설정한다.
 * - 형식: `enc1:` + base64(iv(12B) ‖ GCM ciphertext+tag). 레거시 평문은 접두사가 없으니 그대로 읽는다(점진 암호화).
 * - 유저 대화는 애초에 무저장이라 대상 아님 — 여기는 "관리자가 등록한 설정/문서" 보관 데이터 전용.
 */
object FieldCrypto {
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Volatile private var key: SecretKeySpec? = null
    private val rng = SecureRandom()

    /** 부팅 시 1회 키 주입. 빈 키면 비활성(평문 통과). */
    fun configure(rawKey: String?) {
        key =
            rawKey
                ?.takeIf { it.isNotBlank() }
                ?.let { SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)), "AES") }
    }

    fun encrypt(plain: String?): String? {
        val k = key ?: return plain
        if (plain == null) return null
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored // 레거시 평문 — 그대로
        val k = key ?: return stored
        val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
