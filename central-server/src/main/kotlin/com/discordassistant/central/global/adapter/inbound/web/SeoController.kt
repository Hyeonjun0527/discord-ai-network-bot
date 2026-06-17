package com.discordassistant.central.global.adapter.inbound.web

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SeoController {
    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(SITEMAP_XML)

    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robots(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(ROBOTS_TXT)

    private companion object {
        private const val BASE_URL = "https://discord-ai.yeon.world"

        private val SITEMAP_XML =
            listOf(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">",
                "  <url><loc>$BASE_URL/</loc></url>",
                "  <url><loc>$BASE_URL/install</loc></url>",
                "  <url><loc>$BASE_URL/privacy</loc></url>",
                "  <url><loc>$BASE_URL/terms</loc></url>",
                "  <url><loc>$BASE_URL/legal</loc></url>",
                "</urlset>",
            ).joinToString(separator = "\n")

        private val ROBOTS_TXT =
            listOf(
                "User-agent: *",
                "Allow: /",
                "",
                "Sitemap: $BASE_URL/sitemap.xml",
            ).joinToString(separator = "\n")
    }
}
