package com.discordassistant.central.onboarding.adapter.outbound

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Discord 길드(서버) 요약 — 선택 화면 표시용. */
data class GuildBrief(
    val id: Long,
    val name: String,
)

/**
 * Discord OAuth2(authorization-code) 최소 클라이언트 — 웹 ‘토큰 받기’ 온보딩용.
 * `code` → access token 교환, `identify`+`guilds` 로 사용자 id·소속 길드를 조회한다.
 */
interface DiscordOAuthClient {
    /** authorization code 를 access token 으로 교환한다. 실패 시 null. */
    fun exchangeCodeForToken(
        code: String,
        redirectUri: String,
    ): String?

    /** access token 으로 현재 사용자(Discord) id 를 조회한다. 실패 시 null. */
    fun fetchUserId(accessToken: String): Long?

    /** access token 으로 사용자가 속한 길드 목록을 조회한다(scope=guilds). 실패 시 빈 목록. */
    fun fetchUserGuilds(accessToken: String): List<GuildBrief>
}

/** 실제 Discord API 호출 구현(java.net.http). client-id/secret 으로 토큰 교환. */
@Component
class HttpDiscordOAuthClient(
    @param:org.springframework.beans.factory.annotation.Value("\${central.connect.discord-client-id:}")
    private val clientId: String,
    @param:org.springframework.beans.factory.annotation.Value("\${central.connect.discord-client-secret:}")
    private val clientSecret: String,
) : DiscordOAuthClient {
    private val api = "https://discord.com/api"
    private val log = LoggerFactory.getLogger(HttpDiscordOAuthClient::class.java)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

    override fun exchangeCodeForToken(
        code: String,
        redirectUri: String,
    ): String? {
        val form =
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
            ).entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val req =
            HttpRequest
                .newBuilder(URI.create("$api/oauth2/token"))
                .timeout(java.time.Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("Discord 토큰 교환 실패: {}", resp.statusCode())
                null
            } else {
                mapper
                    .readTree(resp.body())
                    .get("access_token")
                    ?.asText()
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            log.warn("Discord 토큰 교환 오류: {}", e.javaClass.simpleName)
            null
        }
    }

    override fun fetchUserId(accessToken: String): Long? {
        val req =
            HttpRequest
                .newBuilder(URI.create("$api/users/@me"))
                .timeout(java.time.Duration.ofSeconds(8))
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                null
            } else {
                mapper
                    .readTree(resp.body())
                    .get("id")
                    ?.asText()
                    ?.toLongOrNull()
            }
        } catch (e: Exception) {
            log.warn("Discord 사용자 조회 오류: {}", e.javaClass.simpleName)
            null
        }
    }

    override fun fetchUserGuilds(accessToken: String): List<GuildBrief> {
        val req =
            HttpRequest
                .newBuilder(URI.create("$api/users/@me/guilds"))
                .timeout(java.time.Duration.ofSeconds(8))
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                emptyList()
            } else {
                mapper.readTree(resp.body()).mapNotNull { node ->
                    val id = node.get("id")?.asText()?.toLongOrNull() ?: return@mapNotNull null
                    GuildBrief(id, node.get("name")?.asText().orEmpty())
                }
            }
        } catch (e: Exception) {
            log.warn("Discord 길드 조회 오류: {}", e.javaClass.simpleName)
            emptyList()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}

/**
 * OAuth state 저장소(CSRF 방지). state → (로컬 콜백 cb, 로컬 세션키, 만료). 단발성(take 시 제거).
 * 서버 메모리에만 둔다(짧은 TTL). 위조된 콜백이 토큰을 빼가지 못하게 cb 는 발급 시점에 봉인된다.
 */
class ConnectStateStore(
    private val ttlMillis: Long = 600_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Entry(
        val cb: String,
        val localState: String,
        val expiresAt: Long,
    )

    private val map = ConcurrentHashMap<String, Entry>()
    private val rnd = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** (cb, localState) 를 봉인하고 불투명 state 를 돌려준다. */
    fun put(
        cb: String,
        localState: String,
    ): String {
        val now = clock.millis()
        map.values.removeIf { now > it.expiresAt } // 미완료 흐름이 쌓이지 않게 발급 시 만료분 정리.
        val raw = ByteArray(24).also { rnd.nextBytes(it) }
        val state = b64.encodeToString(raw)
        map[state] = Entry(cb, localState, now + ttlMillis)
        return state
    }

    /** state 로 봉인을 회수한다(1회). 없거나 만료면 null. */
    fun take(state: String): Entry? {
        val e = map.remove(state) ?: return null
        return if (clock.millis() > e.expiresAt) null else e
    }
}

/**
 * 서버 선택 저장소 — 후보 길드가 여러 개일 때 사용자 식별 결과(userId·후보·cb)를 짧게 보관하고,
 * 선택 화면의 링크(/provider/connect/pick)에서 회수한다. 단발성 키. 토큰은 선택 후 발급한다.
 */
class ProviderSelectionStore(
    private val ttlMillis: Long = 600_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Entry(
        val cb: String,
        val localState: String,
        val userId: Long,
        val candidates: List<GuildBrief>,
        val expiresAt: Long,
    )

    private val map = ConcurrentHashMap<String, Entry>()
    private val rnd = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun put(
        cb: String,
        localState: String,
        userId: Long,
        candidates: List<GuildBrief>,
    ): String {
        val now = clock.millis()
        map.values.removeIf { now > it.expiresAt }
        val raw = ByteArray(24).also { rnd.nextBytes(it) }
        val key = b64.encodeToString(raw)
        map[key] = Entry(cb, localState, userId, candidates, now + ttlMillis)
        return key
    }

    /** 키로 회수(1회). 없거나 만료면 null. */
    fun take(key: String): Entry? {
        val e = map.remove(key) ?: return null
        return if (clock.millis() > e.expiresAt) null else e
    }
}
