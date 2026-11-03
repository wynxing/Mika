package com.wangzan101.mika.core.remote

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * 远程入口共用的业务错误;HTTP 状态和 MCP isError 由各自适配器决定。
 * 要按入口换说法的几种情形各是一个子类,适配器按类型换,不去比对 message 的文字。
 */
sealed class RemoteTaskError(val code: Code, message: String) : Exception(message) {
    enum class Code { INVALID_ARGUMENT, NOT_FOUND, GONE, CONFLICT, UNAVAILABLE }

    /** 其余错误:message 原样给调用方。 */
    class General(code: Code, message: String) : RemoteTaskError(code, message)
    class NoSuchTask(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task with id $id")
    class NoSuchRecord(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task or run record with id $id")
    class BadConversation : RemoteTaskError(Code.INVALID_ARGUMENT,
        "conversation must be one returned by an earlier remote task (looks like mcp-<uuid>)")
    /** 这一步要用户在手机上处理,不能远程回答。 */
    class NeedsPhone : RemoteTaskError(Code.CONFLICT, RemoteStatus.ON_PHONE_NOTE)
    /** 查到时还在问,交回答时问题已经没了。 */
    class QuestionClosed : RemoteTaskError(Code.CONFLICT, "The question is no longer open; check the task's status.")
}

data class RemoteWait(val task: RemoteTask, val changed: Boolean)
data class RemoteCancellation(val taskId: String, val accepted: Boolean, val status: String? = null)
data class RemoteSteerReceipt(val taskId: String, val messageId: String)

/**
 * CLI API 与 MCP 共用的任务用例。没有 HTTP、JSON-RPC、工具名或命令行参数。
 * 只经 [RemoteTasks] 接入手机上原有的任务服务,不创建另一份执行器或任务状态。
 */
class RemoteTaskService(
    private val tasks: RemoteTasks,
    private val pollMs: Long = 250,
    private val records: RunExportSource? = null,
) {
    init { require(pollMs > 0) }

    val exportEnabled: Boolean get() = records != null

    /**
     * 按任务编号或运行记录编号导出到目前为止的脱敏记录。没有运行记录出口时不可用。
     * 未知编号、手机本地运行都是找不到;记录文件已被清掉时说明是哪一次。
     */
    fun export(id: String): RunRecordExport.Payload {
        val source = records ?: throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, "Run export is unavailable on this connection.")
        val clean = id.trim()
        if (clean.isEmpty() || !ID.matches(clean)) invalid("id must be a task or run id returned by Mika")
        return when (val found = source.load(clean)) {
            RunExportLookup.Missing -> throw RemoteTaskError.NoSuchRecord(clean)
            is RunExportLookup.Gone -> throw RemoteTaskError.General(
                RemoteTaskError.Code.NOT_FOUND,
                "Run record ${found.runId} for task ${found.taskId} is no longer on the phone.",
            )
            is RunExportLookup.Found -> RunRecordExport.of(found.record)
        }
    }

    fun start(goal: String, conversation: String? = null, requestId: String? = null): RemoteTask {
        val clean = required(goal, "goal", MAX_GOAL)
        val thread = conversation?.trim()?.takeIf { it.isNotEmpty() }
        if (thread != null && !isConversation(thread)) throw RemoteTaskError.BadConversation()
        if (requestId != null && !isUuid(requestId)) invalid("request_id must be a canonical lowercase UUID")
        return when (val r = tasks.start(clean, thread, requestId)) {
            is StartOutcome.Started -> r.task
            is StartOutcome.Refused -> throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, r.message)
        }
    }

    fun get(id: String): RemoteTask {
        val clean = id.trim()
        if (clean.isEmpty()) invalid("task_id is required and must be a string")
        return tasks.get(clean) ?: throw RemoteTaskError.NoSuchTask(clean)
    }

    fun list(limit: Int = DEFAULT_LIST): List<RemoteTask> {
        if (limit !in 1..MAX_LIST) invalid("limit must be between 1 and $MAX_LIST")
        return tasks.list(limit)
    }

    /** 一次有界等待,状态变了或终止就返回;等待超时不取消手机任务。等待中任务查不到了,就返回最后看到的状态。 */
    suspend fun wait(id: String, timeoutS: Int = DEFAULT_WAIT_S): RemoteWait {
        val first = get(id)
        if (timeoutS !in 1..MAX_WAIT_S) invalid("timeout_s must be between 1 and $MAX_WAIT_S")
        val from = RemoteStatus.of(first.state)
        var now = first
        var waited = 0L
        while (!RemoteStatus.finished(RemoteStatus.of(now.state)) && RemoteStatus.of(now.state) == from && waited < timeoutS * 1000L) {
            val interval = minOf(pollMs, timeoutS * 1000L - waited)
            delay(interval)
            waited += interval
            now = tasks.get(first.id) ?: break
        }
        return RemoteWait(now, RemoteStatus.of(now.state) != from)
    }

    /** 只能回答追问,不能把付款确认或手机接管当作普通回答。 */
    fun answer(id: String, text: String): String {
        val task = get(id)
        val clean = required(text, "text", MAX_ANSWER)
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_FOR_ANSWER -> Unit
            RemoteStatus.WAITING_ON_PHONE -> throw RemoteTaskError.NeedsPhone()
            else -> conflict("Task ${task.id} is not waiting for an answer (status ${RemoteStatus.of(task.state)}).")
        }
        if (!tasks.answer(task.id, clean)) throw RemoteTaskError.QuestionClosed()
        return task.id
    }

    /** 仅向运行中的任务追加;回答、审批、接管和恢复仍走各自入口。宿主再次核对状态和收件箱。 */
    fun steer(id: String, text: String): RemoteSteerReceipt {
        val task = get(id)
        val clean = required(text, "text", MAX_STEER)
        val status = RemoteStatus.of(task.state)
        if (status == RemoteStatus.WAITING_ON_PHONE) throw RemoteTaskError.NeedsPhone()
        if (status != RemoteStatus.RUNNING) conflict("Task ${task.id} is not running (status $status). " +
            "Answer an open question or wait for it to run; steering does not start or resume a task.")
        val messageId = tasks.steer(task.id, clean)
            ?: conflict("The task no longer accepts instructions; check its status. No new task was started.")
        return RemoteSteerReceipt(task.id, messageId)
    }

    /** accepted 只表示已交给任务服务,是否到 cancelled 终态仍以状态查询为准。 */
    fun cancel(id: String): RemoteCancellation {
        val task = get(id)
        val status = RemoteStatus.of(task.state)
        return if (RemoteStatus.finished(status)) RemoteCancellation(task.id, false, status)
        else RemoteCancellation(task.id, tasks.cancel(task.id))
    }

    private fun required(value: String, name: String, max: Int): String {
        val clean = value.trim()
        if (clean.isEmpty()) invalid("$name is required and must be a non-empty string")
        if (clean.length > max) invalid("$name is too long (at most $max characters)")
        return clean
    }

    private fun invalid(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, message)
    private fun conflict(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, message)

    companion object {
        const val DEFAULT_WAIT_S = 30
        const val MAX_WAIT_S = 50
        const val DEFAULT_LIST = 10
        const val MAX_LIST = 50
        const val MAX_GOAL = 4_000
        const val MAX_ANSWER = 2_000
        const val MAX_STEER = 4_000
        // 保留已发出的对话编号,让新 CLI 与原 MCP 可以继续同一个远程对话。
        private const val CONVERSATION_PREFIX = "mcp-"
        fun newConversation(): String = CONVERSATION_PREFIX + UUID.randomUUID()
        private fun isUuid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun isConversation(id: String): Boolean = id.startsWith(CONVERSATION_PREFIX) && isUuid(id.removePrefix(CONVERSATION_PREFIX))
        private val ID = Regex("[A-Za-z0-9_-]+")
    }
}
