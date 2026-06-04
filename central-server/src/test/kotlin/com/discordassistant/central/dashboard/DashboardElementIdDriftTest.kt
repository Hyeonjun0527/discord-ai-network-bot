package com.discordassistant.central.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 대시보드 element ID 드리프트 가드. `app.js` 가 `$("id")` 헬퍼(=getElementById)로 참조하는 모든
 * **리터럴 ID** 는 `index.html` 에 정적 `id="id"` 로 존재해야 한다. (동적 생성 요소는 `id="${'$'}{...}"`
 * 보간을 쓰므로 `$("리터럴")` 로는 잡히지 않는다 — 따라서 리터럴 참조는 곧 정적 ID 여야 한다.)
 *
 * HTML 의 ID 를 리팩터링하면서 app.js 갱신을 빠뜨리면 런타임에 null 참조로 기능이 죽던 것을 빌드에서 막는다.
 */
class DashboardElementIdDriftTest {
    private fun read(rel: String): String {
        val roots =
            listOf(
                "src/main/resources/static/admin/dashboard/",
                "central-server/src/main/resources/static/admin/dashboard/",
            )
        return roots.map { File(it + rel) }.firstOrNull { it.exists() }?.readText()
            ?: error("$rel 를 찾지 못했습니다 (cwd=${File(".").absolutePath})")
    }

    @Test
    fun `app_js 의 모든 리터럴 element ID 참조는 index_html 에 존재한다`() {
        val js = read("app.js")
        val html = read("index.html")
        val jsRefs =
            Regex("""\$\("([a-zA-Z0-9_-]+)"\)""").findAll(js).map { it.groupValues[1] }.toSet()
        val htmlIds =
            Regex("""id="([a-zA-Z0-9_-]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue(jsRefs.size >= 50, "app.js ID 참조 파싱 오류 의심: ${jsRefs.size}")
        assertEquals(emptySet<String>(), jsRefs - htmlIds, "app.js 가 참조하지만 index.html 에 없는 element ID")
    }
}
