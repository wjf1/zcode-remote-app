package com.zcode.remote.ui.components

import com.zcode.remote.R
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具家族划分，对齐桌面端 `jr({toolName, kind})` 返回的 `family` 字段。
 *
 * 桌面的完整家族表还包含 `changesGroup` / `executeGroup` 这类「聚合组」，
 * 那是桌面把多个同类工具行合并渲染时用的，App 端逐行渲染，故不引入。
 */
enum class ToolFamily {
    FileRead,
    FileWrite,
    Shell,
    Search,
    Todo,
    Goal,
    Explore,
    SessionContext,
    AskUserQuestion,
    Message,
    TaskControl,
    NodeRepl,
    Agent,
    Skill,
    Unknown,
}

/**
 * 工具调用状态，对齐桌面端 `SE`/`bE` 的六态。
 *
 * 桌面有两条状态命名链路（行内 status 与 wire status），App 的协议字段两套都可能出现，
 * 故 [normalizeStatus] 同时接受两套别名。
 */
enum class ToolStatus { Pending, Running, Completed, Failed, Denied, Stopped, Unknown }

/**
 * `toolName → 家族 → 中文文案` 的映射表，数值与文案均取自桌面端渲染器，
 * 保证手机与桌面看到的是同一个词。
 *
 * 全部为纯函数，无 Compose 依赖，便于单测。
 */
object ToolKindLabels {
    /**
     * 桌面端 `wE` 精确匹配表（键为规范名小写）。
     *
     * 桌面用「规范名表 + 小写索引」做大小写不敏感精确匹配，命中即返回、不走正则兜底。
     */
    private val EXACT: Map<String, ToolFamily> =
        mapOf(
            "read" to ToolFamily.FileRead,
            "write" to ToolFamily.FileWrite,
            "edit" to ToolFamily.FileWrite,
            "applypatch" to ToolFamily.FileWrite,
            "bash" to ToolFamily.Shell,
            "glob" to ToolFamily.Search,
            "grep" to ToolFamily.Search,
            "webfetch" to ToolFamily.Search,
            "websearch" to ToolFamily.Search,
            "web_search" to ToolFamily.Search,
            "todoread" to ToolFamily.Todo,
            "todowrite" to ToolFamily.Todo,
            "goalread" to ToolFamily.Goal,
            "readsessioncontext" to ToolFamily.SessionContext,
            "askuserquestion" to ToolFamily.AskUserQuestion,
            "sendmessage" to ToolFamily.Message,
            "respondtocoordinator" to ToolFamily.Message,
            "taskoutput" to ToolFamily.TaskControl,
            "taskstop" to ToolFamily.TaskControl,
            "js" to ToolFamily.NodeRepl,
            "js_reset" to ToolFamily.NodeRepl,
            "js_add_node_module_dir" to ToolFamily.NodeRepl,
            "agent" to ToolFamily.Agent,
            "task" to ToolFamily.Agent,
            "subagent" to ToolFamily.Agent,
            "skill" to ToolFamily.Skill,
        )

    /** 桌面端 `hg` 兜底正则，顺序与桌面一致（先命中先返回）。 */
    private val FALLBACK: List<Pair<Regex, ToolFamily>> =
        listOf(
            Regex("^(?:read|view|open|cat|head|tail|read_file)(?:_|$)") to ToolFamily.FileRead,
            Regex("(?:^|_)(?:edit|patch|replace|multi_edit|multiedit|write|create|save|apply_patch)(?:_|$)") to ToolFamily.FileWrite,
            Regex("^(?:execute|run|exec|bash|shell|command|terminal)(?:_|$)") to ToolFamily.Shell,
            Regex("^(?:search|grep|find|fetch|web_search|web_fetch|webfetch|query|lookup|glob|list|ls|dir|tree)(?:_|$)")
                to ToolFamily.Search,
            Regex("^(?:explore|inspect)(?:_|$)") to ToolFamily.Explore,
        )

    /** 归一化用：连续空白折叠为单个下划线。 */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 解析工具家族。`toolName` 为空时退回 `kind`（桌面同样把 kind 当作 toolName 用）。
     */
    fun familyFor(
        toolName: String?,
        kind: String? = null,
    ): ToolFamily {
        val raw = toolName?.trim().orEmpty().ifEmpty { kind?.trim().orEmpty() }
        if (raw.isEmpty()) return ToolFamily.Unknown

        val lowered = raw.lowercase()
        EXACT[lowered]?.let { return it }

        // MCP 工具形如 `mcp__<server>__<tool>`，桌面在 wE 里把这些名字逐个列全，
        // 这里改为剥前缀再查表，等价但不用堆一长串常量。
        if (lowered.startsWith("mcp__")) {
            val tail = lowered.substringAfterLast("__", missingDelimiterValue = "")
            EXACT[tail]?.let { return it }
            if (tail.startsWith("js")) return ToolFamily.NodeRepl
        }

        // 桌面在跑正则前会把空白与 `-` 统一成 `_`
        val normalized = lowered.replace('-', '_').replace(WHITESPACE, "_")
        FALLBACK.forEach { (regex, family) -> if (regex.containsMatchIn(normalized)) return family }

        // 最后再试一次「去掉分隔符」的写法（如 readFile / writeFile）
        EXACT[normalized.replace("_", "")]?.let { return it }

        return ToolFamily.Unknown
    }

    /**
     * 类型标签（非运行态）。对应桌面端 `chat.toolCall.kind.*`。
     */
    fun label(
        family: ToolFamily,
        toolName: String? = null,
    ): UiText =
        when (family) {
            ToolFamily.FileRead -> res(R.string.tool_label_read)
            ToolFamily.FileWrite -> fileWriteLabel(toolName)
            ToolFamily.Shell -> res(R.string.tool_label_shell)
            ToolFamily.Search -> res(R.string.tool_label_search)
            ToolFamily.Todo -> res(R.string.tool_label_todo)
            ToolFamily.Goal -> res(R.string.tool_label_goal)
            ToolFamily.Explore -> res(R.string.tool_label_explore)
            ToolFamily.SessionContext -> res(R.string.tool_label_session_context)
            ToolFamily.AskUserQuestion -> res(R.string.tool_label_ask_user)
            ToolFamily.Message -> res(R.string.tool_label_message)
            ToolFamily.TaskControl -> res(R.string.tool_label_task)
            ToolFamily.NodeRepl -> res(R.string.tool_label_node_repl)
            ToolFamily.Agent -> res(R.string.tool_label_agent)
            ToolFamily.Skill -> res(R.string.tool_label_skill)
            ToolFamily.Unknown -> res(R.string.tool_label_unknown)
        }

    /**
     * 运行态类型标签。桌面在 running 时把标签整体换成「正在…」措辞。
     * 未定义运行态措辞的家族（如子智能体）沿用非运行态文案。
     */
    fun runningLabel(
        family: ToolFamily,
        toolName: String? = null,
    ): UiText =
        when (family) {
            ToolFamily.FileRead -> res(R.string.tool_running_read)
            ToolFamily.FileWrite ->
                when (fileWriteVerb(toolName)) {
                    FileWriteVerb.Write -> res(R.string.tool_running_write)
                    FileWriteVerb.Edit -> res(R.string.tool_running_edit)
                    FileWriteVerb.Delete -> res(R.string.tool_running_delete)
                }
            ToolFamily.Shell -> res(R.string.tool_running_shell)
            ToolFamily.Search -> res(R.string.tool_running_search)
            ToolFamily.Todo -> res(R.string.tool_running_todo)
            ToolFamily.SessionContext -> res(R.string.tool_running_session_context)
            ToolFamily.AskUserQuestion -> res(R.string.tool_running_ask_user)
            ToolFamily.Skill -> res(R.string.tool_running_skill)
            else -> label(family, toolName)
        }

    private enum class FileWriteVerb { Write, Edit, Delete }

    private fun fileWriteVerb(toolName: String?): FileWriteVerb {
        val name = toolName?.trim()?.lowercase().orEmpty()
        return when {
            name.contains("delete") || name.contains("remove") -> FileWriteVerb.Delete
            name == "write" || name.startsWith("write") -> FileWriteVerb.Write
            name == "edit" || name == "applypatch" || name.contains("patch") || name.contains("replace") ->
                FileWriteVerb.Edit
            else -> FileWriteVerb.Write
        }
    }

    private fun fileWriteLabel(toolName: String?): UiText =
        when (fileWriteVerb(toolName)) {
            FileWriteVerb.Write -> res(R.string.tool_label_write)
            FileWriteVerb.Edit -> res(R.string.tool_label_edit)
            FileWriteVerb.Delete -> res(R.string.tool_label_delete)
        }

    /**
     * 状态归一化，同时接受桌面行内 status 与 wire status 两套命名。
     * 桌面映射：inputStreaming/pendingApproval→pending、running→in_progress、
     * success→completed、error→failed、cancelled→stopped；wire 侧再叠一层连字符写法。
     */
    fun normalizeStatus(raw: String?): ToolStatus {
        val key =
            raw
                ?.trim()
                ?.lowercase()
                ?.replace("-", "")
                ?.replace("_", "")
                .orEmpty()
        return when (key) {
            "inputstreaming", "pending", "pendingapproval", "waiting", "queued" -> ToolStatus.Pending
            "running", "inprogress", "inputavailable", "streaming", "started" -> ToolStatus.Running
            "success", "completed", "outputavailable", "done" -> ToolStatus.Completed
            "error", "failed", "outputerror" -> ToolStatus.Failed
            "denied", "outputdenied", "rejected" -> ToolStatus.Denied
            "cancelled", "canceled", "stopped", "aborted" -> ToolStatus.Stopped
            else -> ToolStatus.Unknown
        }
    }

    /** 状态中文文案。`Unknown` 返回 null，表示不渲染状态字。 */
    fun statusLabel(status: ToolStatus): UiText? =
        when (status) {
            ToolStatus.Pending -> res(R.string.tool_status_pending)
            ToolStatus.Running -> res(R.string.tool_status_running)
            ToolStatus.Completed -> res(R.string.tool_status_completed)
            ToolStatus.Failed -> res(R.string.tool_status_failed)
            ToolStatus.Denied -> res(R.string.tool_status_denied)
            ToolStatus.Stopped -> res(R.string.tool_status_stopped)
            ToolStatus.Unknown -> null
        }

    /** 便捷组合：从原始字符串直达中文状态文案。 */
    fun statusLabel(raw: String?): UiText? = statusLabel(normalizeStatus(raw))

    /**
     * 主文案，对齐桌面端 `S8e` 的计算规则。
     *
     * @param inputJson 工具入参的 JSON 文本（App 协议里 `input` 字段的序列化结果），可为 null。
     */
    fun primaryText(
        toolName: String?,
        kind: String? = null,
        title: String? = null,
        inputJson: String? = null,
    ): UiText? {
        val family = familyFor(toolName, kind)
        val input = parseObject(inputJson)
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }

        return when (family) {
            ToolFamily.FileRead -> {
                val path = firstString(input, "file_path", "filePath", "path") ?: cleanTitle
                path?.let { UiText.Raw(basename(it)) }
            }

            ToolFamily.Search -> searchPrimaryText(input, cleanTitle)

            ToolFamily.Shell -> rawOrNull(shellCommand(input) ?: cleanTitle)

            else ->
                rawOrNull(
                    cleanTitle
                        ?: toolName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: kind?.trim()?.takeIf { it.isNotEmpty() },
                )
        }
    }

    /** 搜索类主文案（`查找 X` / `列出 X`），无查询/路径时回落到 title。 */
    private fun searchPrimaryText(
        input: JsonObject?,
        cleanTitle: String?,
    ): UiText? {
        val query = firstString(input, "query", "pattern", "search_query", "q")
        val cwd = firstString(input, "cwd", "path", "dir", "directory")
        return when {
            !query.isNullOrBlank() -> res(R.string.tool_primary_search_query, query)
            !cwd.isNullOrBlank() -> res(R.string.tool_primary_search_list, cwd)
            // 无查询/路径时回落到 title（服务端下发的原文，不参与本地化）
            else -> rawOrNull(cleanTitle)
        }
    }

    /** 包装运行期文本为 [UiText.Raw]（null 保持 null）——避免调用点多处重复 `?.let { }`。 */
    private fun rawOrNull(value: String?): UiText? = value?.let { UiText.Raw(it) }

    /** 桌面端 `QW`：shell 命令提取（数组取 `-lc` 之后一项，否则空格拼接）。 */
    private fun shellCommand(input: JsonObject?): String? {
        val node =
            input?.get("command")
                ?: input?.get("cmd")
                ?: input?.get("script")
                ?: input?.get("parsed_cmd")
                ?: return null
        val text =
            when (node) {
                is JsonPrimitive -> node.contentOrNullSafe()
                is JsonArray -> {
                    val parts = node.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
                    val lc = parts.indexOf("-lc")
                    if (lc >= 0 && lc + 1 < parts.size) parts[lc + 1] else parts.joinToString(" ")
                }
                else -> null
            }
        // 命令行常带换行与多层缩进，压成单行才是桌面上的观感
        return text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 取路径末段文件名，兼容 `/` 与 `\` 两种分隔符。 */
    fun basename(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return name.ifEmpty { path }
    }

    /**
     * 从工具入参里取被操作文件的末段名。
     *
     * 桌面端写类工具（Edit / Write / ApplyPatch）的折叠态主文案就是文件名。
     * [primaryText] 对该家族只回落到 `toolName`（会显示成英文的 `Edit`），故单独补这一层。
     */
    fun filePathBasename(inputJson: String?): String? =
        firstString(parseObject(inputJson), "file_path", "filePath", "path", "notebook_path")
            ?.let { basename(it) }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun parseObject(text: String?): JsonObject? {
        val body = text?.trim()
        if (body.isNullOrEmpty() || body == "null") return null
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    private fun firstString(
        obj: JsonObject?,
        vararg keys: String,
    ): String? {
        if (obj == null) return null
        keys.forEach { key ->
            val value = (obj[key] as? JsonPrimitive)?.contentOrNullSafe()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** `JsonPrimitive.content` 对 JsonNull 会抛错，这里做一次兜底。 */
    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() }
}
