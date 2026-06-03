package com.discordassistant.central.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
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
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            // API/WS/액추에이터는 stateless — CSRF 비활성(토큰/세션 미사용 경로).
            csrf { disable() }
            if (oauthEnabled) {
                authorizeHttpRequests {
                    // 공개: 정적 대시보드, 다운로드, 읽기 메트릭/헬스, 에이전트 WS, 로그인.
                    authorize("/dashboard/**", permitAll)
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
                    authorize("/actuator/health", permitAll)
                    authorize("/api/metrics/**", permitAll)
                    authorize("/agent/**", permitAll)
                    authorize("/provider/connect/**", permitAll) // 웹 ‘토큰 받기’ OAuth 온보딩
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
