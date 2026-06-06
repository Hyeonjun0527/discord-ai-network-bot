package com.discordassistant.central.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstallPageDownloadLinkTest {
    @Test
    fun `manual downloads use central download route with cache busting`() {
        val html =
            requireNotNull(javaClass.classLoader.getResource("static/install.html")) {
                "install.html resource not found"
            }.readText()

        assertTrue(html.contains("/download/\${name}?v=\${Date.now()}"), html)
        assertTrue(html.contains("nexa-macos.dmg"), html)
        assertTrue(html.contains("nexa-windows.exe"), html)
        assertFalse(html.contains("releases/download/agent-v"), html)
        assertFalse(html.contains("agent-v0."), html)
    }
}
