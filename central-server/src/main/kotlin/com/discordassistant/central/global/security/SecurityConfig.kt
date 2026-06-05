package com.discordassistant.central.global.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.web.SecurityFilterChain

/**
 * 대시보드 관리자 인증(차수 14 #196 / #197, Discord OAuth2).
 *
 * 기본(central.oauth.enabled=false): 공개 읽기 경로는 보존하되 관리자 audience/쓰기 API 는
 * X-Dashboard-Admin-Token 또는 인증 세션이 있어야 한다.
 * 활성(true + Discord OAuth2 등록): 대시보드 데이터·쓰기 경로를 인증 사용자로 제한하고 OAuth2 로그인을 켠다.
 *
 * Discord OAuth2 등록은 spring.security.oauth2.client.registration.discord(client-id·secret, 런타임 시크릿).
 */
@Configuration
class SecurityConfig(
    @param:Value("\${central.oauth.enabled:false}") private val oauthEnabled: Boolean,
    @param:Value("\${central.connect.discord-client-id:}") private val discordClientId: String,
    @param:Value("\${central.connect.discord-client-secret:}") private val discordClientSecret: String,
) {
    /**
     * 대시보드 관리자 Discord 로그인용 OAuth2 클라이언트 등록(central.oauth.enabled=true 일 때만 생성).
     * 기존 ‘토큰 받기’ OAuth 앱(client-id/secret)을 재사용한다. Discord 는 Spring 기본 제공자가 아니라
     * 엔드포인트를 직접 지정. 빈 client-id 로 application.yml 에 등록하면 부팅이 깨지므로 코드로 조건부 생성한다.
     * 디스코드 OAuth 앱에 redirect URI <공개주소>/login/oauth2/code/discord 추가 등록 필요.
     */
    @Bean
    @ConditionalOnProperty(prefix = "central.oauth", name = ["enabled"], havingValue = "true")
    fun discordClientRegistrationRepository(): ClientRegistrationRepository {
        val registration =
            ClientRegistration
                .withRegistrationId("discord")
                .clientId(discordClientId)
                .clientSecret(discordClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/discord")
                .scope("identify")
                .authorizationUri("https://discord.com/api/oauth2/authorize")
                .tokenUri("https://discord.com/api/oauth2/token")
                .userInfoUri("https://discord.com/api/users/@me")
                .userNameAttributeName("id")
                .clientName("Discord")
                .build()
        return InMemoryClientRegistrationRepository(registration)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            // API/WS/액추에이터는 stateless — CSRF 비활성(토큰/세션 미사용 경로).
            csrf { disable() }
            if (oauthEnabled) {
                authorizeHttpRequests {
                    // 어드민 대시보드(/admin/dashboard/**)는 **인증 필요** — 미로그인 브라우저 접근은
                    // oauth2Login 진입점이 디스코드 OAuth 로 자동 리디렉트한다(아래 anyRequest authenticated).
                    // 공개: 로그인 상태 조회(/api/me), 정적 다운로드, 읽기 메트릭/헬스, 에이전트 WS, 로그인.
                    authorize("/api/me", permitAll)
                    authorize("/presets", permitAll)
                    authorize("/presets/**", permitAll)
                    authorize("/api/ai-network/features", permitAll)
                    authorize("/api/ai-network/presets/catalog", permitAll)
                    authorize("/api/ai-network/presets/catalog/**", permitAll)
                    authorize("/api/ai-network/presets/published/*/like", permitAll)
                    authorize("/api/ai-network/presets/published/*/report", permitAll)
                    authorize("/download/**", permitAll) // 에이전트 바이너리 공개 다운로드
                    authorize("/", permitAll) // 설치 랜딩(차수 19)
                    authorize("/install", permitAll)
                    authorize("/install.html", permitAll)
                    authorize("/img/**", permitAll) // 랜딩 이미지(마스코트 등)
                    authorize("/i18n/**", permitAll) // 웹 문구 i18n(공개 정적, 설치 랜딩 다국어)
                    authorize("/actuator/health", permitAll)
                    authorize("/api/metrics/**", permitAll)
                    authorize("/agent/**", permitAll)
                    authorize("/provider/connect/**", permitAll) // 웹 ‘토큰 받기’ OAuth 온보딩
                    authorize("/provider/agent/**", permitAll) // 에이전트 자동 동기화(durable 토큰으로 자체 인증)
                    authorize("/login/**", permitAll)
                    authorize("/oauth2/**", permitAll)
                    // 그 외(대시보드 데이터/쓰기 API)는 인증 필요.
                    authorize(anyRequest, authenticated)
                }
                oauth2Login { }
            } else {
                // 기본: Spring Security 는 공개 허용하되 AiNetworkApiSecurityFilter 가 관리자 audience/쓰기 API 를 보호한다.
                authorizeHttpRequests {
                    authorize(anyRequest, permitAll)
                }
            }
        }
        return http.build()
    }
}
