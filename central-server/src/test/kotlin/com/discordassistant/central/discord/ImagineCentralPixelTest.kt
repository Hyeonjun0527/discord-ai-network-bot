package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.routing.application.CloudImageBackend
import com.discordassistant.central.routing.application.CloudImageException
import com.discordassistant.central.routing.application.ImageReview
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional

/**
 * central 이 픽셀까지 직접 만드는 클라우드 SD 백엔드 fake. 테스트가 활성/실패를 주입하고 호출된
 * 프롬프트·negative 를 기록한다(실 Stability/RunPod 호출 없음).
 */
class FakeCloudImageBackend : CloudImageBackend {
    var enabled = true
    var throws = false
    var lastPrompt: String? = null
    var lastNegative: String? = null
    var lastWidth = 0
    var lastHeight = 0
    var png = byteArrayOf(10, 20, 30)

    override fun isEnabled() = enabled

    override fun txt2img(
        prompt: String,
        width: Int,
        height: Int,
        negativePrompt: String,
    ): ByteArray {
        lastPrompt = prompt
        lastNegative = negativePrompt
        lastWidth = width
        lastHeight = height
        if (throws) throw CloudImageException("픽셀 생성 실패")
        return png
    }

    override fun defaultResolution(): Pair<Int, Int> = 1024 to 1024
}

@TestConfiguration
class FakeCloudImagePixelConfig {
    @Bean
    @Primary
    fun fakeCloudLlm(): FakeCloudLlm = FakeCloudLlm()

    @Bean
    @Primary
    fun fakeCloudImageBackend(): FakeCloudImageBackend = FakeCloudImageBackend()
}

/**
 * ADR 0006 단계4 — central 직접 픽셀 생성. 클라우드 SD 키가 있으면 **에이전트 풀이 없어도** central 이
 * 심사→번역→픽셀까지 만든다(완전 앱리스). 심사(cloudLlm) 없이는 픽셀 생성 안 함(fail-closed).
 * Reply 캡션·형식(🖼️ ☁️ "원문")은 에이전트 클라우드 경로와 동일 — 게시확인 게이트 호환.
 */
@SpringBootTest(properties = ["central.relay.public-url=wss://discord-ai.yeon.world/agent"])
@org.springframework.context.annotation.Import(FakeCloudImagePixelConfig::class)
@Transactional
class ImagineCentralPixelTest
    @Autowired
    constructor(
        val commands: CommandService,
        val cloudLlm: FakeCloudLlm,
        val cloudImage: FakeCloudImageBackend,
    ) {
        private fun reset() {
            cloudLlm.enabled = true
            cloudLlm.reviewThrows = false
            cloudLlm.translateThrows = false
            cloudLlm.review = ImageReview(allowed = true, reason = "정상", category = "safe")
            cloudImage.enabled = true
            cloudImage.throws = false
            cloudImage.lastPrompt = null
        }

        @Test
        fun `클라우드 SD 키 있고 에이전트 풀 비어도 central 이 직접 픽셀 생성`() {
            reset()
            cloudLlm.translation = "masterpiece, best quality, safe, a cute cat"
            // 이미지 프로바이더를 전혀 등록하지 않는다(풀 빔) — central 직접 경로만으로 생성돼야 한다.
            val ctx = CommandContext(guildId = 8001, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
            val r = commands.imagine(ctx, "고양이")
            // 번역 결과가 픽셀 생성에 전달됨
            assertEquals("masterpiece, best quality, safe, a cute cat", cloudImage.lastPrompt)
            assertTrue(cloudImage.lastNegative!!.isNotBlank()) // forcedNegative 주입
            assertEquals(1024, cloudImage.lastWidth)
            assertEquals(1024, cloudImage.lastHeight)
            // Reply: 캡션은 원문, ☁️ 출처, imagePng 채워짐(게시게이트 호환 형식 그대로)
            assertTrue(r.content.startsWith("🖼️ ☁️ \"고양이\""), r.content)
            assertTrue(r.imagePng != null)
            assertEquals(false, r.ephemeral)
            assertArrayEquals(byteArrayOf(10, 20, 30), r.imagePng!!)
        }

        @Test
        fun `심사 거부면 픽셀 생성 안 함(fail-closed)`() {
            reset()
            cloudLlm.review = ImageReview(allowed = false, reason = "안전 위반", category = "sexual")
            val ctx = CommandContext(guildId = 8002, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
            val r = commands.imagine(ctx, "위험한 요청")
            assertTrue(r.content.contains("안전 정책상 만들 수 없어요"), r.content)
            assertNull(r.imagePng)
            assertNull(cloudImage.lastPrompt) // 픽셀 백엔드 미호출
        }

        @Test
        fun `심사 자체 실패면 픽셀 생성 안 함(fail-closed)`() {
            reset()
            cloudLlm.reviewThrows = true
            val ctx = CommandContext(guildId = 8003, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
            val r = commands.imagine(ctx, "고양이")
            assertTrue(r.content.contains("안전 심사를 완료하지 못해"), r.content)
            assertNull(r.imagePng)
            assertNull(cloudImage.lastPrompt) // 심사 실패면 픽셀로 넘어가지 않음
        }

        @Test
        fun `심사 활성이지만 클라우드 SD 키 없으면 기존 에이전트 풀 경로로 폴백`() {
            reset()
            cloudImage.enabled = false // 클라우드 SD 비활성 → 에이전트 풀 필요
            cloudImage.lastPrompt = null
            // 에이전트 이미지 프로바이더 미등록 → 풀 비어 안내 메시지(직접 경로 아님)
            val ctx = CommandContext(guildId = 8004, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
            val r = commands.imagine(ctx, "고양이")
            assertTrue(r.content.contains("이미지를 만들 수 있는 곳이 없어요"), r.content)
            assertNull(cloudImage.lastPrompt) // central 직접 픽셀 미사용
        }

        @Test
        fun `번역 실패해도 원문으로 직접 픽셀 생성(차단 아님)`() {
            reset()
            cloudLlm.translateThrows = true
            val ctx = CommandContext(guildId = 8005, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
            val r = commands.imagine(ctx, "강아지")
            assertEquals("강아지", cloudImage.lastPrompt) // 번역 실패 → 원문으로 생성
            assertTrue(r.imagePng != null)
        }

        @Test
        fun `픽셀 생성 실패면 친화 메시지(명령 깨지지 않음)`() {
            reset()
            cloudImage.throws = true
            val ctx = CommandContext(guildId = 8006, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
            val r = commands.imagine(ctx, "고양이")
            assertTrue(r.content.contains("이미지 생성에 실패"), r.content)
            assertNull(r.imagePng)
        }
    }
