package com.discordassistant.central.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UrlSafetyTest {
    @Test
    fun `사설·루프백·로컬·비http·userinfo 차단(SSRF)`() {
        // 아래는 DNS 불필요(IP 리터럴/이름 규칙)라 네트워크 없이 판정됨.
        assertFalse(UrlSafety.isFetchAllowed("https://localhost/x"))
        assertFalse(UrlSafety.isFetchAllowed("https://api.internal/x"))
        assertFalse(UrlSafety.isFetchAllowed("https://nas.local/x"))
        assertFalse(UrlSafety.isFetchAllowed("https://127.0.0.1/x"))
        assertFalse(UrlSafety.isFetchAllowed("https://10.0.0.5/x"))
        assertFalse(UrlSafety.isFetchAllowed("https://192.168.1.10/x"))
        assertFalse(UrlSafety.isFetchAllowed("https://169.254.1.1/x")) // link-local
        assertFalse(UrlSafety.isFetchAllowed("ftp://example.com/x")) // 비 http(s)
        assertFalse(UrlSafety.isFetchAllowed("https://user@8.8.8.8/x")) // userinfo
        assertFalse(UrlSafety.isFetchAllowed("not a url"))
    }

    @Test
    fun `public IP 리터럴은 허용`() {
        // 공인 IP 리터럴은 DNS 불필요 + public → 허용.
        assertTrue(UrlSafety.isFetchAllowed("https://8.8.8.8/x"))
        assertTrue(UrlSafety.isFetchAllowed("http://93.184.216.34/"))
    }
}

class HtmlTextTest {
    @Test
    fun `script·style 제거 + 태그 제거 + 공백 정리`() {
        val html =
            """
            <html><head><style>.a{color:red}</style><script>alert(1)</script></head>
            <body><h1>제목</h1>  <p>본문&nbsp;텍스트 &amp; 더보기</p></body></html>
            """.trimIndent()
        val t = HtmlText.extract(html)
        assertFalse(t.contains("alert"))
        assertFalse(t.contains("color:red"))
        assertFalse(t.contains("<"))
        assertTrue(t.contains("제목"))
        assertTrue(t.contains("본문 텍스트 & 더보기"))
    }

    @Test
    fun `maxChars 로 길이 제한`() {
        val long = "<p>" + "가".repeat(5000) + "</p>"
        assertEquals(100, HtmlText.extract(long, maxChars = 100).length)
    }
}
