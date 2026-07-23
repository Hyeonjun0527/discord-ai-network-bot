package com.discordassistant.central.platform.discord.admin

import com.discordassistant.central.routing.application.CloudToolCall
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * AI 관리 비서 tool calling SSOT. [toolsJson] 은 GLM 에 보낼 OpenAI function schema 배열의 JSON 문자열이고,
 * [SYSTEM_PROMPT] 는 GLM 이 자연어 관리 명령을 도구 호출로 옮기도록 안내하는 지침이다. GLM 이 돌려준
 * [CloudToolCall] 은 [toPlan] 으로 순수 파싱한다(JDA 미참조 — 단위 테스트 가능).
 *
 * 대상(사용자/채널)은 멘션·이름·ID 어느 형태로 와도 GLM 이 ID 를 추출하도록 description 에 명시하고,
 * 실행 단계([AdminActionExecutor])가 이름→엔티티 폴백 해석도 시도한다.
 */
object AdminToolCatalog {
    private val mapper = ObjectMapper()

    /**
     * 어드민의 자연어 /질문 에 함께 보내는 시스템 지침. 관리 의도가 명확할 때만 도구를 호출하고,
     * 일반 질문이면 도구를 호출하지 말라고 안내한다(비관리 질문 보존). 한국어 사용자 대상.
     */
    val SYSTEM_PROMPT =
        """
        당신은 Discord 서버 관리자를 돕는 관리 비서입니다.
        사용자가 서버 관리 작업(채널 만들기/삭제, 이름·주제·슬로우모드 변경, 멤버 차단/추방/타임아웃/해제 등)을
        요청하면, 가장 알맞은 도구(function)를 호출하세요.

        - 명확한 관리 작업 요청일 때만 도구를 호출하세요. 일반 질문·잡담·정보 요청이면 도구를 호출하지 말고
          `NOT_ADMIN_ACTION` 한 줄만 출력하세요. 이 텍스트는 사용자에게 보이지 않고 기존 /질문 경로가 답합니다.
        - 대상 사용자/채널은 멘션(<@123>, <#456>), 이름("철수", "잡담채널"), 또는 ID 어느 형태로든 올 수 있습니다.
          가능하면 숫자 ID(멘션 안의 숫자 포함)를 추출해 넣고, ID 가 없으면 이름 문자열을 그대로 넣으세요.
        - 한 번에 하나의 작업만 호출하세요. 확실하지 않으면 도구를 호출하지 말고 무엇을 원하는지 되물으세요.
        """.trimIndent()

    /** OpenAI function schema 배열의 JSON 문자열(GLM `tools` 파라미터). */
    val toolsJson: String by lazy { buildToolsJson() }

    /**
     * GLM tool_call 을 실행 의도로 파싱한다. 모르는 함수면 null, 인자 JSON 이 깨졌으면 빈 인자로 안전 폴백한다
     * (실행 단계가 필수 인자 부재를 graceful 안내). 문자열/숫자/불리언 인자는 모두 문자열로 평탄화한다.
     */
    fun toPlan(call: CloudToolCall): AdminActionPlan? {
        val type = AdminActionType.fromToolName(call.name) ?: return null
        val args =
            runCatching {
                val node = mapper.readTree(call.argumentsJson)
                if (node == null || !node.isObject) {
                    emptyMap()
                } else {
                    buildMap {
                        node.fields().forEach { (key, value) ->
                            val text = if (value.isTextual) value.asText() else value.toString()
                            if (text.isNotBlank() && text != "null") put(key, text)
                        }
                    }
                }
            }.getOrElse { emptyMap() }
        return AdminActionPlan(type = type, args = args)
    }

    private fun buildToolsJson(): String {
        val tools = mapper.createArrayNode()

        fun tool(
            name: String,
            description: String,
            params: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit,
            required: List<String>,
        ) {
            val fn = mapper.createObjectNode()
            fn.put("name", name).put("description", description)
            val parameters = fn.putObject("parameters")
            parameters.put("type", "object")
            val properties = parameters.putObject("properties")
            params(properties)
            val req = parameters.putArray("required")
            required.forEach { req.add(it) }
            tools.addObject().put("type", "function").set<com.fasterxml.jackson.databind.JsonNode>("function", fn)
        }

        fun com.fasterxml.jackson.databind.node.ObjectNode.strProp(
            name: String,
            desc: String,
        ) {
            putObject(name).put("type", "string").put("description", desc)
        }

        fun com.fasterxml.jackson.databind.node.ObjectNode.intProp(
            name: String,
            desc: String,
        ) {
            putObject(name).put("type", "integer").put("description", desc)
        }

        val targetUserDesc = "대상 사용자. 멘션 안의 숫자 ID 를 우선 추출(예: <@123>→\"123\"). 없으면 표시 이름 문자열."
        val targetChannelDesc = "대상 채널. 멘션 안의 숫자 ID 를 우선 추출(예: <#456>→\"456\"). 없으면 채널 이름 문자열."

        tool(
            "create_text_channel",
            "새 텍스트 채널을 만든다.",
            {
                it.strProp("name", "만들 채널 이름")
                it.strProp("categoryId", "넣을 카테고리 ID 또는 이름(선택)")
                it.strProp("topic", "채널 주제(선택)")
            },
            listOf("name"),
        )
        tool(
            "create_voice_channel",
            "새 음성 채널을 만든다.",
            {
                it.strProp("name", "만들 음성 채널 이름")
                it.strProp("categoryId", "넣을 카테고리 ID 또는 이름(선택)")
            },
            listOf("name"),
        )
        tool(
            "create_category",
            "새 카테고리를 만든다.",
            { it.strProp("name", "만들 카테고리 이름") },
            listOf("name"),
        )
        tool(
            "rename_channel",
            "기존 채널의 이름을 바꾼다.",
            {
                it.strProp("channelId", targetChannelDesc)
                it.strProp("newName", "새 채널 이름")
            },
            listOf("channelId", "newName"),
        )
        tool(
            "set_channel_topic",
            "채널 주제(topic)를 설정한다.",
            {
                it.strProp("channelId", targetChannelDesc)
                it.strProp("topic", "설정할 주제 문구")
            },
            listOf("channelId", "topic"),
        )
        tool(
            "set_slowmode",
            "텍스트 채널의 슬로우모드(초)를 설정한다(0=해제, 최대 21600).",
            {
                it.strProp("channelId", targetChannelDesc)
                it.intProp("seconds", "슬로우모드 초(0~21600)")
            },
            listOf("channelId", "seconds"),
        )
        tool(
            "unban_member",
            "차단된 사용자를 차단 해제한다.",
            { it.strProp("userId", "차단 해제할 사용자 ID(차단 해제는 ID 가 필요)") },
            listOf("userId"),
        )
        tool(
            "remove_timeout",
            "사용자의 타임아웃(글쓰기 제한)을 해제한다.",
            { it.strProp("userId", targetUserDesc) },
            listOf("userId"),
        )
        tool(
            "ban_member",
            "사용자를 서버에서 차단(ban)한다. 위험 작업 — 확인 후 실행된다.",
            {
                it.strProp("userId", targetUserDesc)
                it.strProp("reason", "차단 사유(선택)")
                it.intProp("deleteMessageDays", "최근 N일 메시지 삭제(0~7, 선택)")
            },
            listOf("userId"),
        )
        tool(
            "kick_member",
            "사용자를 서버에서 추방(kick)한다. 위험 작업 — 확인 후 실행된다.",
            {
                it.strProp("userId", targetUserDesc)
                it.strProp("reason", "추방 사유(선택)")
            },
            listOf("userId"),
        )
        tool(
            "timeout_member",
            "사용자를 N분 동안 타임아웃(글쓰기 제한)한다. 위험 작업 — 확인 후 실행된다.",
            {
                it.strProp("userId", targetUserDesc)
                it.intProp("minutes", "타임아웃 분(1~40320)")
                it.strProp("reason", "사유(선택)")
            },
            listOf("userId", "minutes"),
        )
        tool(
            "delete_channel",
            "채널을 삭제한다. 위험 작업 — 확인 후 실행된다.",
            { it.strProp("channelId", targetChannelDesc) },
            listOf("channelId"),
        )
        tool(
            "set_channel_permission",
            "채널에서 특정 역할/사용자의 권한을 허용/거부한다. 위험 작업 — 확인 후 실행된다.",
            {
                it.strProp("channelId", targetChannelDesc)
                it.strProp("targetId", "권한을 줄/막을 역할 또는 사용자 ID")
                it.strProp("allow", "허용할 권한 이름들(쉼표 구분, 선택). 예: VIEW_CHANNEL,MESSAGE_SEND")
                it.strProp("deny", "거부할 권한 이름들(쉼표 구분, 선택). 예: MESSAGE_SEND")
            },
            listOf("channelId", "targetId"),
        )
        return mapper.writeValueAsString(tools)
    }
}
