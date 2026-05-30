package com.discordassistant.central.discord

/**
 * 긴 출력 페이지네이션(차수 13 #187). Discord 메시지 한도(2000자) 아래로 줄 경계에서 분할한다.
 * 한 줄이 한도를 넘으면 그 줄을 강제로 잘라 넣는다(데이터 유실 방지).
 */
object Pagination {
    const val DISCORD_LIMIT = 2000
    const val DEFAULT_LIMIT = 1900 // 코드블록/접미사 여유

    fun paginate(text: String, limit: Int = DEFAULT_LIMIT): List<String> {
        require(limit > 0) { "limit 은 양수여야 합니다" }
        if (text.length <= limit) return listOf(text)
        val pages = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.split("\n")) {
            // 한 줄 자체가 한도 초과 → 청크로 강제 분할
            if (line.length > limit) {
                if (sb.isNotEmpty()) { pages.add(sb.toString()); sb.clear() }
                var i = 0
                while (i < line.length) {
                    pages.add(line.substring(i, minOf(i + limit, line.length)))
                    i += limit
                }
                continue
            }
            val addition = if (sb.isEmpty()) line.length else line.length + 1
            if (sb.length + addition > limit) {
                pages.add(sb.toString())
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(line)
        }
        if (sb.isNotEmpty()) pages.add(sb.toString())
        return pages
    }
}
