package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "操作 Nexus 同会话共享的内置浏览器，不会切换到外部浏览器。一次调用只执行一个 action；通常先 navigate，再用 get_readable 提取正文或 find_elements 查找元素。遇到用户登录、验证码、网站授权或扫码，应调用 open_for_user 打开当前网页供用户接管，随后立即调用 request_user_input 等待用户操作并确认；用户可从浏览器菜单使用相机扫码或相册识码，扫码内容须由用户确认后打开。接管期间不得继续浏览器动作；用户返回并确认后先重新读取页面状态再继续。不要索要密码、验证码或登录Cookie。部分禁止内嵌浏览器或依赖特殊弹窗通信的OAuth网站可能需要用户使用外部应用，外部应用登录态不会自动导入。需要把URI显式交给外部应用时使用 open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "本次唯一执行的浏览器动作。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("open_for_user")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate 要访问的 URL。")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "click、type、get_text、find_elements 或 wait_for_selector 使用的 CSS selector。")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "type 要输入的文本。只会发送给工具，不会显示在运行摘要中。")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "type 输入后是否提交所在表单，默认 false。")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 X 坐标，和 coordinate_y 一起使用。")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 Y 坐标，和 coordinate_x 一起使用。")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "scroll 的滚动像素量。")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "scroll 的滚动方向。")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 的文本起始偏移，默认 0。")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 最多返回的文本字符数。")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "screenshot 时是否把截图附给模型直接查看，默认 true。")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "navigate 或 wait_for_selector 的超时毫秒数。")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }

}
