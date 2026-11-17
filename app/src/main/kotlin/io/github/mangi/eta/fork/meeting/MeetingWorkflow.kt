package io.github.mangi.eta.fork.meeting

import org.json.JSONArray
import org.json.JSONObject

/** These rules are executed by the scheduler, rather than being privacy instructions to a model. */
internal enum class MeetingStageActors { HOST, MEMBERS, FIRST, SECOND, REVIEWERS }
internal enum class MeetingStageVisibility { PRIVATE, PUBLIC, DIRECTED, ANONYMOUS }
internal enum class MeetingStageCondition { ALWAYS, REWORK }

internal data class MeetingWorkflowStage(
    val id: String,
    val title: String,
    val actors: MeetingStageActors,
    val visibility: MeetingStageVisibility,
    val inputs: List<String>,
    val instruction: String,
    val condition: MeetingStageCondition = MeetingStageCondition.ALWAYS,
    val verification: Boolean = false,
    val projectGraph: Boolean = false,
    val userClarification: Boolean = false,
)

internal data class MeetingWorkflowTemplate(
    val id: String,
    val title: String,
    val description: String,
    val minParticipants: Int,
    val stages: List<MeetingWorkflowStage>,
    val version: Int = 1,
    val maxCalls: Int = 40,
    val maxMinutes: Int = 20,
    val maxOutputChars: Int = 240_000,
) {
    fun encode(): String = JSONObject().put("id", id).put("version", version).put("title", title)
        .put("description", description).put("minParticipants", minParticipants)
        .put("maxCalls", maxCalls).put("maxMinutes", maxMinutes).put("maxOutputChars", maxOutputChars)
        .put("stages", JSONArray(stages.map { stage ->
            JSONObject().put("id", stage.id).put("title", stage.title).put("actors", stage.actors.name)
                .put("visibility", stage.visibility.name).put("inputs", JSONArray(stage.inputs))
                .put("instruction", stage.instruction).put("condition", stage.condition.name)
                .put("verification", stage.verification).put("projectGraph", stage.projectGraph)
                .put("userClarification", stage.userClarification)
        })).toString()

    companion object {
        fun decode(raw: String): MeetingWorkflowTemplate {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1) { "不支持此会议流程版本，未启动执行" }
            val array = root.getJSONArray("stages")
            require(array.length() in 1..12)
            val stages = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val refs = item.getJSONArray("inputs")
                MeetingWorkflowStage(item.getString("id"), item.getString("title"),
                    MeetingStageActors.valueOf(item.getString("actors")),
                    MeetingStageVisibility.valueOf(item.getString("visibility")),
                    (0 until refs.length()).map(refs::getString), item.getString("instruction"),
                    MeetingStageCondition.valueOf(item.optString("condition", "ALWAYS")),
                    item.optBoolean("verification"), item.optBoolean("projectGraph"), item.optBoolean("userClarification"))
            }
            val known = mutableSetOf<String>()
            stages.forEach { stage ->
                require(stage.inputs.all { it in known } && known.add(stage.id)) { "会议阶段依赖无效" }
            }
            return MeetingWorkflowTemplate(root.getString("id"), root.getString("title"), root.getString("description"),
                root.getInt("minParticipants").coerceIn(1, 8), stages, root.getInt("version"),
                root.getInt("maxCalls").coerceIn(1, 64), root.getInt("maxMinutes").coerceIn(1, 60),
                root.getInt("maxOutputChars").coerceIn(12_000, 480_000))
        }
    }
}

internal object MeetingWorkflowCatalog {
    private fun s(id: String, title: String, actors: MeetingStageActors, visibility: MeetingStageVisibility,
        refs: List<String> = emptyList(), instruction: String, condition: MeetingStageCondition = MeetingStageCondition.ALWAYS,
        verification: Boolean = false, project: Boolean = false, clarify: Boolean = false) =
        MeetingWorkflowStage(id, title, actors, visibility, refs, instruction, condition, verification, project, clarify)
    private val host = MeetingStageActors.HOST
    private val members = MeetingStageActors.MEMBERS
    private val first = MeetingStageActors.FIRST
    private val second = MeetingStageActors.SECOND
    private val review = MeetingStageActors.REVIEWERS
    private val hidden = MeetingStageVisibility.PRIVATE
    private val shared = MeetingStageVisibility.PUBLIC
    private val directed = MeetingStageVisibility.DIRECTED
    private val anonymous = MeetingStageVisibility.ANONYMOUS
    private const val VERIFY = "独立验证实际产物与验收证据；不得凭作者声称成功就通过。返回 JSON：{\"status\":\"pass|fail|blocked\",\"evidence\":[\"可复核证据\"],\"issues\":[\"未通过事项\"]}。没有实际证据用 blocked。"

    val all: List<MeetingWorkflowTemplate> = listOf(
        MeetingWorkflowTemplate("M01", "方案讨论 / 头脑风暴", "独立提案 → 公开讨论 → 分歧回应 → 主持归纳；最多三轮", 2, listOf(
            s("ideas", "独立提案", members, hidden, instruction = "独立提出备选方案、依据与风险；本阶段不接收其他成员意见。"),
            s("discussion", "公开讨论", members, shared, listOf("ideas"), "比较已经发布的提案，明确支持点、异议与新增方案，不重复自己的首轮。"),
            s("response", "定向回应", members, directed, listOf("discussion"), "回应交给你的具体分歧，给出修正或证据；明确仍未解决的问题。"),
            s("conclusion", "方案与取舍", host, shared, listOf("ideas", "discussion", "response"), "归纳备选方案、取舍与未决问题，最终选择留给用户。"))),
        MeetingWorkflowTemplate("M02", "资料研究 / 文献调查", "拆问题 → 独立收集 → 去重归源 → 冲突核验 → 研究结论", 2, listOf(
            s("questions", "研究范围", host, hidden, instruction = "拆出研究问题与覆盖标准，为各成员分配互补渠道；不编造来源。"),
            s("collect", "分渠道收集", members, hidden, listOf("questions"), "按你的成员序号选一个互补渠道独立收集；每条证据包含来源URL/文献、发布日期/访问日期、原始依据、覆盖问题。"),
            s("sources", "去重归源", host, shared, listOf("collect"), "按原始来源去重，列出同源转载、冲突证据、覆盖空白和需复核的具体主张。"),
            s("verify", "证据核验", review, directed, listOf("collect", "sources"), "核验交给你的来源与冲突，访问可用原始材料；区分已证实、无法访问和未核实。"),
            s("conclusion", "研究结论", host, shared, listOf("sources", "verify"), "形成有来源与日期的结论，保留相反证据、覆盖空白与预算限制。"))),
        MeetingWorkflowTemplate("M03", "独立审查 / 交叉复审", "至少两位独立初审 → 交换具体问题 → 复核争议 → 裁决", 2, listOf(
            s("audit", "独立初审", members, hidden, instruction = "独立审查用户材料，逐项给出位置、证据、严重度、触发条件；不接收他人初审。"),
            s("recheck", "交叉复核", members, directed, listOf("audit"), "只复核交给你的另一位审查者的问题及对应材料，逐项确认、排除或未决，并说明证据。"),
            s("decision", "争议裁决", host, shared, listOf("audit", "recheck"), "去重问题，保留原始证据、严重度、复核状态与未决项；不能以多数票替代证据。"))),
        MeetingWorkflowTemplate("M04", "多模型竞答 / 事实核验", "同题独答 → 对齐答案 → 分歧核验 → 证据结论", 2, listOf(
            s("answers", "同题独答", members, hidden, instruction = "独立回答同一用户问题，列出依据与不确定性；不要猜测其他答案。"),
            s("compare", "答案对齐", host, shared, listOf("answers"), "对齐各答案的主张与依据，定位矛盾，形成需要验证的事实清单；不要用票数判真伪。"),
            s("factcheck", "分歧核验", members, directed, listOf("answers", "compare"), "针对交付的分歧寻找独立可复核证据；必要时否定自己的初答。"),
            s("conclusion", "可信依据", host, shared, listOf("compare", "factcheck"), "列出共识、仍有分歧与可信依据；证据不足明确保留。"))),
        MeetingWorkflowTemplate("M05", "规划—实施—验证", "确认计划 → 实施 → 独立验证 → 最多一次返工复验", 2, listOf(
            s("plan", "约束与计划", host, hidden, instruction = "根据授权任务形成可实施计划、约束、产物和可验证的验收条件；不自行实施。"),
            s("build", "实施", first, shared, listOf("plan"), "按计划实际实施，记录改动与产物引用、可复核证据和阻塞，不把计划当成完成。"),
            s("verify", "独立验证", review, shared, listOf("plan", "build"), VERIFY, verification = true),
            s("repair", "有界返工", first, shared, listOf("plan", "build", "verify"), "仅修复验证明确未通过项，保留已完成产物；最多此一轮返工。", MeetingStageCondition.REWORK),
            s("reverify", "返工复验", review, shared, listOf("plan", "repair", "verify"), VERIFY, MeetingStageCondition.REWORK, verification = true),
            s("delivery", "交付与验收", host, shared, listOf("plan", "build", "verify", "repair", "reverify"), "仅在验证有证据通过时说明验收通过；其余标明部分完成/阻塞，列实际产物与残余问题。"))),
        MeetingWorkflowTemplate("M06", "故障会诊", "独立假设 → 选择高信息实验 → 实验 → 排除假设 → 根因建议", 2, listOf(
            s("hypotheses", "独立诊断", members, hidden, instruction = "区分已知现象、独立根因假设、可证伪条件和所需事实，不把猜测说成事实。"),
            s("experiments", "实验选择", host, shared, listOf("hypotheses"), "按预期信息收益、风险和成本选择最少实验，列每项实验能排除的假设及停止条件；不授权超范围操作。"),
            s("observations", "复现与实验", first, shared, listOf("experiments"), "实际执行允许的实验，报告操作、原始观察、失败和无法复现之处；不要先下根因结论。"),
            s("eliminate", "假设排除", review, shared, listOf("hypotheses", "observations"), "只据实验事实排除或保留假设，指出是否形成根因证据闭环。"),
            s("diagnosis", "根因与修复建议", host, shared, listOf("observations", "eliminate"), "给出有证据的根因、排除依据、修复建议和残余风险；未闭环明确阻塞，不无限实验。"))),
        MeetingWorkflowTemplate("M07", "写作与审校", "提纲 → 草稿v1 → 结构编辑 → 事实审校 → 定稿v2", 2, listOf(
            s("outline", "提纲", host, hidden, instruction = "确认读者、目的、结构与交付标准，给作者一个明确提纲。"),
            s("draft", "草稿 v1", first, shared, listOf("outline"), "按提纲写完整草稿v1，引用可追溯，未核实内容明确标记。"),
            s("structure", "结构编辑", second, shared, listOf("outline", "draft"), "对草稿v1提出结构与逻辑修改清单；不要同时覆盖作者版本。"),
            s("facts", "事实审校", review, shared, listOf("draft", "structure"), "核验事实与引用，逐项给出处、日期与核实状态；仅提交审校意见。"),
            s("final", "定稿 v2", first, shared, listOf("draft", "structure", "facts"), "根据审校生成唯一完整定稿v2，附引用和仍未核实内容，修订预算到此为止。"),
            s("delivery", "交付", host, shared, listOf("final", "facts"), "交付定稿v2引用及剩余核实事项，不再改写第三个版本。"))),
        MeetingWorkflowTemplate("M08", "正反辩论 / 红蓝演练", "私有立场 → 正方论证 → 反方论证 → 质询 → 回应 → 裁判", 2, listOf(
            s("positions", "私有立场", members, hidden, instruction = "成员序号1为正方/蓝方，2为反方/红方，其余为边界观察者；准备本方立场，仅提交正式立场要点，不输出内部思考。"),
            s("affirm", "正方论证", first, directed, listOf("positions"), "仅据自己私有立场正式提出正方论据、证据与适用边界。"),
            s("oppose", "反方论证", second, shared, listOf("affirm"), "正式回应正方论证，提出反证、漏洞或红方场景。"),
            s("question", "正方质询", first, shared, listOf("oppose"), "回答反方最强论点，并提出两项可回答的关键质询。"),
            s("answer", "反方回应", second, shared, listOf("question"), "逐项回应质询，承认证据不足之处；此后不再循环争辩。"),
            s("judge", "裁判归纳", host, shared, listOf("affirm", "oppose", "question", "answer"), "按证据、逻辑、边界评估双方，列漏洞、防护与取舍，不以语气决定胜负。"))),
        MeetingWorkflowTemplate("M09", "德尔菲 / 专家共识", "独立意见 → 去身份聚合 → 重新评价 → 收敛分析；两轮", 2, listOf(
            s("opinions", "独立意见", members, hidden, instruction = "独立给出判断、估计区间、置信程度与依据；不署名、不提模型/机构身份。"),
            s("aggregate", "匿名聚合", host, anonymous, listOf("opinions"), "仅输出去身份统计/共识区间/少数意见；不要输出成员编号与原话映射，去掉自报身份。"),
            s("reevaluate", "重新评价", members, anonymous, listOf("aggregate"), "只据去身份聚合重新评价自己的结论，报告区间变化、保留意见与原因；不推断身份。"),
            s("convergence", "收敛分析", host, anonymous, listOf("aggregate", "reevaluate"), "比较两轮聚合的区间与分歧，报告收敛程度和少数意见；轮次已到上限，不能伪造全体一致。"))),
        MeetingWorkflowTemplate("M10", "多方案决策评估", "公开维度权重 → 独立评分 → 证据对照 → 敏感性分析", 2, listOf(
            s("criteria", "维度与权重", host, shared, instruction = "输出候选方案、评价维度、权重（合计1）、计分尺度和关键假设。用户未给权重时明确标为待用户确认的暂定权重，后续不得暗改。"),
            s("scores", "独立评分", members, hidden, listOf("criteria"), "独立对所有候选按同一公开权重评分，输出评分表、证据、缺失数据及假设；不接收别人的分数。"),
            s("evidence", "评分证据对照", members, directed, listOf("criteria", "scores"), "核对交给你的评分依据，指出主观判断、冲突和关键缺失，不按人头投票。"),
            s("sensitivity", "敏感性分析", first, shared, listOf("criteria", "scores", "evidence"), "在明确列出的权重变化场景下计算或分析排序变化，保留基准权重；指出何时推荐翻转。"),
            s("recommendation", "决策建议", host, shared, listOf("criteria", "scores", "evidence", "sensitivity"), "给出基准评分表、权重、关键假设与排序变动；最终业务选择和暂定权重确认留给用户。"))),
        MeetingWorkflowTemplate("M11", "项目拆解 / 并行交付", "校验任务依赖与写入归属 → 依赖调度 → 集成 → 独立验收", 2, listOf(
            s("graph", "任务与写入归属", host, hidden, instruction = "仅输出JSON任务图：{\"tasks\":[{\"id\":\"t1\",\"participant\":\"启用的成员profile\",\"instruction\":\"具体任务和接口\",\"depends_on\":[],\"write_scopes\":[\"项目内路径或资源\"]}]}。每人最多一项，必须无环；并发任务写入范围不得重叠；验收者不承担实施。"),
            s("work", "依赖调度与交付", members, directed, listOf("graph"), "按已校验任务图执行，只写自己拥有的资源，接收已完成依赖的成果与接口；遇冲突停止并报告。", project = true),
            s("integrate", "集成", first, shared, listOf("graph", "work"), "仅在所有必需依赖交付后集成；列接口冲突与实际集成证据。不能把未完成依赖标成成功。"),
            s("accept", "独立验收", review, shared, listOf("graph", "work", "integrate"), VERIFY, verification = true),
            s("delivery", "交付状态", host, shared, listOf("work", "integrate", "accept"), "列每项任务、依赖、产物与验收证据；必需依赖失败或验收未通过只可标部分完成。"))),
        MeetingWorkflowTemplate("M12", "需求访谈 / 澄清确认", "整理已知 → 独立找缺口 → 用户追问 → 已确认范围与未知项", 2, listOf(
            s("known", "已知需求", host, hidden, instruction = "整理用户已确认的需求、约束、验收条件与明确未知；历史推测不得冒充确认。"),
            s("gaps", "决策缺口", members, hidden, listOf("known"), "独立识别会改变方案的关键缺口，提出最多三项可回答的问题，检查需求边界；不代替用户回答。"),
            s("interview", "向用户澄清", host, shared, listOf("known", "gaps"), "合并最关键问题，调用现有 request_user_input 向真实用户结构化追问；完整收到回答后才更新已确认需求。拒绝/超时/缺工具都保持未知，不能代选。最后输出 JSON {\"status\":\"answered|unresolved\",\"confirmed\":[],\"unknown\":[]}。", clarify = true),
            s("specification", "范围与验收", host, shared, listOf("known", "gaps", "interview"), "输出需求规格、约束、验收条件与未决项；只有真实回答或用户明确接受假设才可确认相应范围。"))),
    )

    fun find(id: String): MeetingWorkflowTemplate? = all.firstOrNull { it.id == id }
}
