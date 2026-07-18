package com.discordassistant.central.discord

import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.ChunkFrame
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.ImageReview
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

/** task=image 요청을 받으면 PNG 로 응답하고 보낸 InferRequest 를 기록하는 연결 — central 심사/번역 검증용. */
private class RecordingImageConn : AgentConnection {
    lateinit var session: ProviderSession
    var lastInfer: InferRequest? = null
    override val remoteId = "rec-img"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest && frame.task == "image") {
            lastInfer = frame
            val b64 =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(byteArrayOf(1, 2, 3))
            session.handleFrame(ChunkFrame(frame.requestId, b64, done = false))
            session.handleFrame(ChunkFrame(frame.requestId, "", done = true))
        }
    }

    override fun close(reason: String) {}
}

/**
 * central 이 직접 z.ai(GLM)로 이미지를 심사/번역하는 fake. 테스트가 결과를 주입해 거부/통과/번역을
 * 결정하고, 호출된 프롬프트를 기록한다(실 z.ai 호출 없음).
 */
class FakeCloudLlm : CloudLlm {
    var enabled = true
    var review: ImageReview = ImageReview(allowed = true, reason = "정상", category = "safe")
    var reviewThrows = false
    var translateThrows = false
    var translation = "masterpiece, best quality, safe, a translated english prompt"
    var lastReviewedPrompt: String? = null
    var lastTranslatedPrompt: String? = null

    override fun isEnabled() = enabled

    override fun generate(
        prompt: String,
        model: String,
    ): CloudLlmResult = throw CloudLlmException("미사용")

    override fun generateWithTools(
        systemPrompt: String,
        userPrompt: String,
        toolsJson: String,
        model: String,
    ): com.discordassistant.central.routing.application.CloudToolResponse = throw CloudLlmException("미사용")

    override fun reviewImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): ImageReview {
        lastReviewedPrompt = prompt
        if (reviewThrows) throw CloudLlmException("심사 실패")
        return review
    }

    override fun translateImagePrompt(
        prompt: String,
        systemPrompt: String,
    ): String {
        lastTranslatedPrompt = prompt
        if (translateThrows) throw CloudLlmException("번역 실패")
        return translation
    }
}

@TestConfiguration
class FakeCloudLlmConfig {
    @Bean
    @Primary
    fun fakeCloudLlm(): FakeCloudLlm = FakeCloudLlm()
}

/**
 * /그림 central 직접 심사/번역(ADR 0006 단계2). 키 있으면 central 이 심사·번역, 거부 시 생성 안 함,
 * 통과 시 번역된 프롬프트 + preTranslated=true 로 에이전트 위임. 캡션은 원문 보존(동작보존).
 */
@SpringBootTest(properties = ["central.relay.public-url=wss://discord-ai.yeon.world/agent"])
@org.springframework.context.annotation.Import(FakeCloudLlmConfig::class)
@Transactional
class ImagineCentralReviewTest
    @Autowired
    constructor(
        val commands: CommandService,
        val registry: ConnectionRegistry,
        val cloudLlm: FakeCloudLlm,
    ) {
        private fun registerImageProvider(guildId: Long): Pair<ProviderSession, RecordingImageConn> {
            val conn = RecordingImageConn()
            val session = ProviderSession(conn, providerId = 70, guildId = guildId)
            conn.session = session
            session.capability = session.capability.copy(capabilities = listOf("text", "image", "image-cloud"))
            registry.register(session)
            return session to conn
        }

        @Test
        fun `키 있고 심사 거부 → 생성 안 하고 에이전트에 전송 안 함`() {
            cloudLlm.enabled = true
            cloudLlm.review = ImageReview(allowed = false, reason = "안전 위반", category = "sexual")
            val (session, conn) = registerImageProvider(7001)
            try {
                val ctx = CommandContext(guildId = 7001, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
                val r = commands.imagine(ctx, "위험한 요청")
                assertTrue(r.content.contains("안전 정책상 만들 수 없어요"), r.content)
                assertTrue(r.content.contains("안전 위반"), r.content) // reason 노출
                assertNull(r.imagePng) // 이미지 없음
                assertNull(conn.lastInfer) // 픽셀 생성으로 넘어가지 않음
            } finally {
                registry.unregister(session)
            }
        }

        @Test
        fun `키 있고 심사 자체 실패 → fail-closed 차단(생성 안 함)`() {
            cloudLlm.enabled = true
            cloudLlm.reviewThrows = true
            val (session, conn) = registerImageProvider(7002)
            try {
                val ctx = CommandContext(guildId = 7002, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
                val r = commands.imagine(ctx, "고양이")
                assertTrue(r.content.contains("안전 심사를 완료하지 못해"), r.content)
                assertNull(r.imagePng)
                assertNull(conn.lastInfer) // 심사 실패면 에이전트로 보내지 않음
            } finally {
                cloudLlm.reviewThrows = false
                registry.unregister(session)
            }
        }

        @Test
        fun `키 있고 심사 통과 → 번역된 프롬프트 + preTranslated=true 로 전송, 캡션은 원문`() {
            cloudLlm.enabled = true
            cloudLlm.review = ImageReview(allowed = true, reason = "정상", category = "safe")
            cloudLlm.translation = "masterpiece, best quality, safe, a cute cat sitting on a sofa"
            val (session, conn) = registerImageProvider(7003)
            try {
                val ctx = CommandContext(guildId = 7003, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
                val r = commands.imagine(ctx, "고양이")
                // 심사·번역이 원문으로 호출됨
                assertEquals("고양이", cloudLlm.lastReviewedPrompt)
                assertEquals("고양이", cloudLlm.lastTranslatedPrompt)
                // 픽셀 생성에는 번역 결과가 전달됨
                assertEquals("masterpiece, best quality, safe, a cute cat sitting on a sofa", conn.lastInfer!!.prompt)
                // imagePolicy: 시스템 프롬프트 없이 forcedNegative + preTranslated=true
                val policy = conn.lastInfer!!.imagePolicy!!
                assertEquals(true, policy["preTranslated"])
                assertTrue(policy.containsKey("forcedNegative"))
                assertNull(policy["safetySystemPrompt"]) // central 이 이미 심사했으므로 미전달
                assertNull(policy["translatorSystemPrompt"])
                // 캡션은 사용자 원문(번역문 아님) — 동작보존
                assertTrue(r.content.startsWith("🖼️ ☁️ \"고양이\""), r.content)
                assertTrue(r.imagePng != null)
            } finally {
                registry.unregister(session)
            }
        }

        @Test
        fun `키 있고 번역 실패 → 원문 폴백으로 생성(차단 아님)`() {
            cloudLlm.enabled = true
            cloudLlm.review = ImageReview(allowed = true, reason = "정상", category = "safe")
            cloudLlm.translateThrows = true
            val (session, conn) = registerImageProvider(7004)
            try {
                val ctx = CommandContext(guildId = 7004, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
                val r = commands.imagine(ctx, "강아지")
                assertEquals("강아지", conn.lastInfer!!.prompt) // 번역 실패 → 원문으로 생성
                assertEquals(true, conn.lastInfer!!.imagePolicy!!["preTranslated"])
                assertTrue(r.imagePng != null)
            } finally {
                cloudLlm.translateThrows = false
                registry.unregister(session)
            }
        }

        @Test
        fun `OpenAI 키가 없으면 provider로 보내지 않고 fail closed 한다`() {
            cloudLlm.enabled = false
            cloudLlm.lastReviewedPrompt = null // 공유 fake — 이전 테스트 기록 초기화
            cloudLlm.lastTranslatedPrompt = null
            val (session, conn) = registerImageProvider(7005)
            try {
                val ctx = CommandContext(guildId = 7005, channelId = 200, userId = 1, roleIds = setOf(1L), isAdmin = true)
                val r = commands.imagine(ctx, "고양이")
                assertNull(conn.lastInfer)
                assertNull(cloudLlm.lastReviewedPrompt)
                assertNull(cloudLlm.lastTranslatedPrompt)
                assertNull(r.imagePng)
                assertTrue(r.content.contains("안전 심사"))
            } finally {
                registry.unregister(session)
            }
        }
    }
