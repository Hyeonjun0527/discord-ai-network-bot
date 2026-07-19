package com.discordassistant.central.global.security

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

    /** 대시보드/프리셋 디렉터리 URL → index.html 포워드. + 설치 랜딩(차수 19). */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/admin/dashboard").setViewName("forward:/admin/dashboard/index.html")
        registry.addViewController("/admin/dashboard/").setViewName("forward:/admin/dashboard/index.html")
        registry.addViewController("/admin/console").setViewName("forward:/admin/console/index.html")
        registry.addViewController("/admin/console/").setViewName("forward:/admin/console/index.html")
        registry.addViewController("/presets").setViewName("forward:/presets/index.html")
        registry.addViewController("/presets/").setViewName("forward:/presets/index.html")
        // 설치 랜딩 페이지(/, /install)는 InstallPageController 가 SSOT(InstallGuide)를 주입해 서빙한다.
    }
}
