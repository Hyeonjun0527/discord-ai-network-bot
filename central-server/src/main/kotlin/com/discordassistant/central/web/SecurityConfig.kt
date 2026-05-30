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
 * 기본(central.oauth.enabled=false): permitAll — 기존 오픈 API·대시보드 동작과 테스트를 보존한다.
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
                    // 공개: 정적 대시보드, 읽기 메트릭/헬스, 에이전트 WS, 로그인.
                    authorize("/dashboard/**", permitAll)
                    authorize("/actuator/health", permitAll)
                    authorize("/api/metrics/**", permitAll)
                    authorize("/agent/**", permitAll)
                    authorize("/login/**", permitAll)
                    authorize("/oauth2/**", permitAll)
                    // 그 외(대시보드 데이터/쓰기 API)는 인증 필요.
                    authorize(anyRequest, authenticated)
                }
                oauth2Login { }
            } else {
                // 기본: 전부 허용(현 오픈 설계 보존). 운영 인증은 oauth 활성으로 전환.
                authorizeHttpRequests {
                    authorize(anyRequest, permitAll)
                }
            }
        }
        return http.build()
    }
}
