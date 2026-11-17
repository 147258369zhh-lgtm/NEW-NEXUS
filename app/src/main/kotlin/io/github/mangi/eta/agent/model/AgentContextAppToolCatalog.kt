package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "获取手机当前时间、时区和最近系统位置；涉及现在、今天、明天或所在位置时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。每个 Run 最多 3 个显示、总计最多 6 个；new_session 与 session_id 互斥。新显示不保证 Android 会克隆应用或其登录/数据状态。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                                .put(
                                    "display",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("auto").put("virtual").put("main"))
                                        .put("default", "auto")
                                        .put(
                                            "description",
                                            "启动目标画布：auto（默认，跨应用任务优先在后台虚拟屏执行，不打断用户当前主屏）、virtual（显式在后台虚拟屏启动）、main（显式在物理主屏前台打开）。"
                                        )
                                )
                                .put(
                                    "new_session",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "可选；true 时优先新建此 Run 的额外虚拟显示，而不是复用它已有的首选显示。不可与 session_id 同时提供。")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 128)
                                        .put("description", "可选的不透明显示会话 ID；选择此 Run 已拥有的指定显示。只能与 new_session 互斥使用。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "present_to_user",
                    description = "将后台虚拟屏中已经准备就绪的应用画面无缝放大并移交到物理主屏前台，供用户直接查看、完成验证或确认付款。当任务在虚拟屏内已完成前置操作、到达收银台/二维码/结果页，或遇到安全窗口需要用户本人操作时调用。可选 session_id 选择此 Run 已拥有的显示。会话 ID 是不透明值；切换显示后必须重新 observe_screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "reason",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，向用户呈现画面的简短原因，例如“已到达支付确认页”")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 128)
                                        .put("description", "可选的不透明会话 ID；必须属于当前 Run。切换后先重新 observe_screen。")
                                )
                        )
                        .put("additionalProperties", false),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "把一个确定有效的 URI 显式交给 Android 外部应用处理，例如 https、tel、geo 或应用 deep link。它不用于读取网页或网页交互。不要编造 URI。new_session 与 session_id 互斥；每个 Run 最多 3 个显示、总计最多 6 个。Android 不保证在新显示中克隆应用或其数据状态。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                                .put(
                                    "new_session",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "可选；true 时优先为此 Run 新建额外虚拟显示。不可与 session_id 同时提供。")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 128)
                                        .put("description", "可选的不透明会话 ID；选择此 Run 已拥有的指定显示。只能与 new_session 互斥使用。")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "取得手机操作权并观察当前屏幕；其他会话占用时由 Runtime 等待。可选 session_id 选择此 Run 已拥有的显示；会话 ID 是不透明值，切换显示后必须重新 observe_screen。每个 Run 最多 3 个显示、总计最多 6 个。默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。节点为空、目标无法唯一识别、界面以 Canvas/地图/图片/二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，显式设置 include_screenshot=true；补截图时保持 include_ui_tree=true，以同一次新观察刷新节点和 observation_id，禁止把新截图与旧节点混用。节点动作必须原样携带同一次观察的 observation_id；树被截断但节点语义仍有效时，优先把 max_nodes 提高到 120 后重试。手机操作阶段完成后调用 release_gui，让其他会话继续；释放或用户接管后必须重新观察才能操作。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "是否附加当前屏幕原图给模型，默认 false；仅在 UI 节点不足以完成任务时显式开启")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "是否返回 UI 节点列表，默认 true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "最多返回 1 到 120 个 UI 节点，默认 60")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 128)
                                        .put("description", "可选的不透明会话 ID；必须属于当前 Run。切换后本次观察会针对该显示。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "release_gui",
                    description = "结束当前手机操作阶段并释放操作权，让其他会话使用手机。纯思考、网页、Shell 或文件工作不需要继续占用手机。释放后旧观察、节点与坐标全部失效；再次操作前先 observe_screen。没有持有操作权时调用也会成功。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("additionalProperties", false),
                )
            )
    }
}
