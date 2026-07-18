package com.discordassistant.central.global.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 필드 단위 at-rest 암호화(안전정책 B5). 보관 데이터(시스템 프롬프트·전역 프롬프트·RAG 프리뷰)에 적용한다.
 *
 * - 키: `NEXA_FIELD_ENC_KEY`(env). 일반 레거시 필드는 미설정이면 평문 통과하지만, scheduled Discord routing
 *   metadata 저장 경계는 키 미설정을 거부한다. 라우팅 복원은 [decryptOrNull]로 별도 fail-closed 한다.
 * - 형식: `enc1:` + base64(iv(12B) ‖ GCM ciphertext+tag). 레거시 평문은 접두사가 없으니 그대로 읽는다(점진 암호화).
 * - 유저 대화는 애초에 무저장이라 대상 아님 — 여기는 "관리자가 등록한 설정/문서" 보관 데이터 전용.
 */
object FieldCrypto {
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Volatile private var key: SecretKeySpec? = null
    private val rng = SecureRandom()

    /** 부팅 시 1회 키 주입. 빈 키면 비활성(일반 레거시 필드는 평문 통과). */
    fun configure(rawKey: String?) {
        key =
            rawKey
                ?.takeIf { it.isNotBlank() }
                ?.let { SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)), "AES") }
    }

    fun isConfigured(): Boolean = key != null

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
        return decryptEncrypted(stored, k)
    }

    /**
     * 실행 라우팅처럼 암호문을 평문으로 오인하면 안 되는 경계에서 사용한다. 키 누락·오류·손상은 예외나 암호문 대신
     * `null`로 격리하며, 호출자는 해당 행동을 fail-closed 해야 한다.
     */
    fun decryptOrNull(stored: String?): String? {
        if (stored == null || !isEncrypted(stored)) return stored
        val k = key ?: return null
        return try {
            decryptEncrypted(stored, k)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    private fun decryptEncrypted(
        stored: String,
        k: SecretKeySpec,
    ): String {
        val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        require(raw.size > IV_LEN) { "encrypted field payload is too short" }
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
