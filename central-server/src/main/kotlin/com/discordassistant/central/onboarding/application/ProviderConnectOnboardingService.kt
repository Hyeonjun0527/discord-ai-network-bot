package com.discordassistant.central.onboarding.application

import com.discordassistant.central.guild.application.AutoApprovePolicy
import com.discordassistant.central.onboarding.adapter.outbound.ConnectStateStore
import com.discordassistant.central.onboarding.adapter.outbound.DiscordOAuthClient
import com.discordassistant.central.onboarding.adapter.outbound.GuildBrief
import com.discordassistant.central.onboarding.adapter.outbound.ProviderSelectionStore
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.provider.application.ProviderRegistrationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 웹 ‘토큰 받기’ OAuth 온보딩 오케스트레이션(컨트롤러에서 핵심 유스케이스를 이관, #lean).
 *
 * 흐름: connect → Discord OAuth(identify+guilds) → callback(봇 길드 ∩ 사용자 길드 후보 산출 + 0/1/N 분기)
 * → 1개면 자동/N개면 선택 → 가입(자동승인 정책)·일회용 토큰 발급. 결과는 [ConnectResult]/[CallbackResult]
 * 세일드 타입으로 돌려주고, HTTP/HTML 매핑은 컨트롤러+[..adapter.inbound.web.ConnectPageRenderer] 가 한다.
 *
 * 보안 보존:
 * - client-id/secret 미설정이면 비활성(503): [enabled]/[ConnectResult.NotEnabled].
 * - cb 는 localhost+경로 `/connect/callback` 만 허용([isLocalCallback]) — 토큰 탈취 방지.
 * - OAuth state·선택 키는 서버 발급·단발성(CSRF/재생 방지): [ConnectStateStore]/[ProviderSelectionStore]
 *   의 SecureRandom·TTL·1회 take 로직을 그대로 사용한다.
 * - 토큰 발급 정책(자동승인·재발급 폴백) 분기·메시지 보존: [issue] 의 requestJoin→issueOnboardingToken 폴백.
 *   requestJoin/issueOnboardingToken 의 TX 경계는 합치지 않는다(각 서비스 호출 그대로).
 */
@Service
class ProviderConnectOnboardingService(
    @param:Value("\${central.connect.discord-client-id:}") private val clientId: String,
    @param:Value("\${central.connect.discord-client-secret:}") private val clientSecret: String,
    @param:Value("\${central.connect.public-base-url:}") private val configuredBase: String,
    @param:Value("\${central.relay.public-url:}") private val relayPublicUrl: String,
    private val oauth: DiscordOAuthClient,
    private val registration: ProviderRegistrationService,
    private val policy: AutoApprovePolicy,
    private val botGuilds: BotGuildLister,
    private val states: ConnectStateStore,
    private val selections: ProviderSelectionStore,
) {
    private companion object {
        const val CALLBACK_PATH = "/connect/callback"
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")
    }

    /** OAuth 활성 여부(설정 노출 없이 버튼 토글 판단용). */
    val enabled: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** connect 단계 결과(세일드). */
    sealed interface ConnectResult {
        /** 미설정 → 503 안내. */
        data object NotEnabled : ConnectResult

        /** 잘못된 콜백 주소 → 400. */
        data object InvalidCallback : ConnectResult

        /** 세션 정보 없음(state 공백) → 400. */
        data object MissingState : ConnectResult

        /** Discord authorize 로 리디렉트. */
        data class Redirect(
            val authorizeUrl: String,
        ) : ConnectResult
    }

    /** callback/pick 단계 결과(세일드). */
    sealed interface CallbackResult {
        /** 만료/잘못된 state 또는 선택 키 → 400. */
        data object InvalidRequest : CallbackResult

        /** 선택 화면에서 고를 수 없는 서버 → 400. */
        data object InvalidSelection : CallbackResult

        /** 사용자가 취소(code 없음) → cb?error=cancelled. */
        data class Cancelled(
            val cb: String,
            val localState: String,
        ) : CallbackResult

        /** 토큰 교환 실패 → cb?error=token. */
        data class TokenFailed(
            val cb: String,
            val localState: String,
        ) : CallbackResult

        /** 사용자 식별 실패 → cb?error=identify. */
        data class IdentifyFailed(
            val cb: String,
            val localState: String,
        ) : CallbackResult

        /** 봇 있는 서버 중 내가 속한 곳 없음 → 안내 페이지. */
        data object NoCandidate : CallbackResult

        /** 토큰 발급 성공 → cb?token=…&guild=…&guildName=…. */
        data class Issued(
            val cb: String,
            val localState: String,
            val token: String,
            val guildId: Long,
            val guildName: String,
        ) : CallbackResult

        /** 승인 대기(PENDING) → cb?error=pending. */
        data class Pending(
            val cb: String,
            val localState: String,
        ) : CallbackResult

        /** 후보가 여러 개 → 선택 화면(단발성 sel 키 + 후보). */
        data class NeedSelection(
            val sel: String,
            val candidates: List<GuildBrief>,
        ) : CallbackResult
    }

    /** connect: 설정·콜백·state 검증 후 OAuth authorize URL 을 산출한다(state 봉인 포함). */
    fun connect(
        cb: String,
        state: String,
    ): ConnectResult {
        if (!enabled) return ConnectResult.NotEnabled
        if (!isLocalCallback(cb)) return ConnectResult.InvalidCallback
        if (state.isBlank()) return ConnectResult.MissingState
        val oauthState = states.put(cb, state)
        val authorize =
            "https://discord.com/api/oauth2/authorize?response_type=code" +
                "&client_id=" + enc(clientId) +
                "&scope=" + enc("identify guilds") +
                "&redirect_uri=" + enc(callbackRedirectUri()) +
                "&state=" + enc(oauthState) +
                "&prompt=consent"
        return ConnectResult.Redirect(authorize)
    }

    /**
     * callback: state 회수 → 토큰 교환 → 사용자 식별 → (봇 길드 ∩ 사용자 길드) 후보 산출 → 0/1/N 분기.
     * 1개면 즉시 발급([issue]), N개면 선택 키를 봉인해 선택 화면으로([CallbackResult.NeedSelection]).
     */
    fun callback(
        code: String?,
        state: String?,
    ): CallbackResult {
        val entry = state?.let { states.take(it) } ?: return CallbackResult.InvalidRequest
        if (code.isNullOrBlank()) return CallbackResult.Cancelled(entry.cb, entry.localState)
        val accessToken =
            oauth.exchangeCodeForToken(code, callbackRedirectUri())
                ?: return CallbackResult.TokenFailed(entry.cb, entry.localState)
        val userId =
            oauth.fetchUserId(accessToken)
                ?: return CallbackResult.IdentifyFailed(entry.cb, entry.localState)
        // 봇이 있는 서버 ∩ 내가 속한 서버 = 프로바이더가 될 수 있는 후보.
        val botIds = botGuilds.botGuildIds()
        val candidates = oauth.fetchUserGuilds(accessToken).filter { it.id in botIds }
        return when {
            candidates.isEmpty() -> CallbackResult.NoCandidate
            candidates.size == 1 ->
                issue(entry.cb, entry.localState, userId, candidates[0].id, candidates[0].name)
            else -> {
                val sel = selections.put(entry.cb, entry.localState, userId, candidates)
                CallbackResult.NeedSelection(sel, candidates)
            }
        }
    }

    /** pick: 선택 키 회수 → 고른 서버 검증 → 발급([issue]). */
    fun pick(
        sel: String,
        guild: Long,
    ): CallbackResult {
        val entry = selections.take(sel) ?: return CallbackResult.InvalidRequest
        val picked = entry.candidates.firstOrNull { it.id == guild } ?: return CallbackResult.InvalidSelection
        return issue(entry.cb, entry.localState, entry.userId, guild, picked.name)
    }

    /**
     * 선택된 서버에 가입(자동 승인 정책)·토큰 발급. 자동 승인 토큰이 없으면 issueOnboardingToken 로 폴백한다
     * (재설치·재페어링). 둘 다 없으면 관리자 승인 대기(PENDING). 토큰 정책·메시지·분기 그대로.
     */
    private fun issue(
        cb: String,
        localState: String,
        userId: Long,
        guildId: Long,
        guildName: String,
    ): CallbackResult {
        val join = registration.requestJoin(userId, guildId, autoApprove = policy.isAutoApprove(guildId))
        val token = join.token ?: registration.issueOnboardingToken(userId, guildId)
        return if (token != null) {
            CallbackResult.Issued(cb, localState, token, guildId, guildName)
        } else {
            CallbackResult.Pending(cb, localState)
        }
    }

    /** OAuth redirect_uri(공개 베이스 + /provider/connect/callback). connect/callback 에서 동일하게 쓴다. */
    private fun callbackRedirectUri(): String = publicBase() + "/provider/connect/callback"

    /** 공개 베이스 URL(설정값 우선, 없으면 relay wss→https 변환 + 끝 `/agent` 제거). */
    private fun publicBase(): String {
        val raw =
            configuredBase.ifBlank {
                relayPublicUrl.replace("wss://", "https://").replace("ws://", "http://").let {
                    if (it.endsWith("/agent")) it.dropLast("/agent".length) else it
                }
            }
        return raw.trim().trimEnd('/')
    }

    /** cb 가 localhost 콜백(http://127.0.0.1:PORT/connect/callback)인지 엄격 검증(토큰 탈취 방지). */
    private fun isLocalCallback(cb: String): Boolean {
        val uri = runCatching { URI.create(cb) }.getOrNull() ?: return false
        if (uri.scheme != "http") return false
        if (uri.userInfo != null) return false
        if (uri.host == null || uri.host !in LOCAL_HOSTS) return false
        if (uri.path != CALLBACK_PATH) return false
        if (uri.query != null || uri.fragment != null) return false
        return true
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
