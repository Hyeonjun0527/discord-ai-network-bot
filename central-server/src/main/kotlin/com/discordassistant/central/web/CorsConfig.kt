package com.discordassistant.central.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS 설정(차수 14 #209). 대시보드 프론트엔드 오리진만 허용한다. 기본은 빈 목록(=교차 출처 차단).
 * `central.dashboard.cors-origins` 에 콤마구분 오리진을 설정해 허용.
 */
@Configuration
class CorsConfig(
    @param:Value("\${central.dashboard.cors-origins:}") private val origins: String,
    @param:Value("\${central.download.dir:/app/downloads}") private val downloadDir: String,
) : WebMvcConfigurer {
    /** 프로바이더 에이전트 단일 실행파일 서빙(우리 도메인에서 직접 — 레포 비공개 유지). */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/download/**")
            .addResourceLocations("file:${downloadDir.removeSuffix("/")}/")
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        val allowed = origins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (allowed.isEmpty()) return // 미설정 시 교차 출처 비허용(안전 기본값)
        registry
            .addMapping("/api/**")
            .allowedOrigins(*allowed.toTypedArray())
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowCredentials(true)
            .maxAge(3600)
    }

    /** 대시보드 디렉터리 URL(/dashboard, /dashboard/) → index.html 포워드(차수 14). + 설치 랜딩(차수 19). */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/dashboard").setViewName("forward:/dashboard/index.html")
        registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html")
        // 프로바이더 설치 랜딩 페이지: 루트(/)와 /install 둘 다 노출(차수 19 UX).
        registry.addViewController("/").setViewName("forward:/install.html")
        registry.addViewController("/install").setViewName("forward:/install.html")
    }
}
