package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * RikkaHub 以「单条对话为截止点重 roll」时，上一次分支已成功执行的
 * hold/grow 在 Ombre-Brain 里是永久副作用；重新生成又会写一份，导致
 * 记忆库出现大量重复桶。
 *
 * 这个模块负责给 Ombre-Brain 的写入型工具注入稳定对话锚点：
 *
 *     client_write_context = {
 *         "client": "rikkahub",
 *         "conversation_id": "<conversation UUID>",
 *         "anchor_node_id": "<触发本次生成的稳定用户消息节点 UUID>",
 *         "generation_id": "<本次生成动作 UUID，审计用>",
 *         "reroll_generation": 0-based 的第几次生成
 *     }
 *
 * Ombre-Brain 服务端用 (conversation_id, anchor_node_id) 建立持久化写入
 * 幂等账本：同锚点同 payload 复用首次结果，措辞不同但事实相同的 reroll
 * 复用既有桶，从而不再堆积重复记忆。
 *
 * 设计要点：
 * - 只对已由 Ombre-Brain 实现 receipt 幂等语义的写入工具（hold / grow）
 *   注入；plan / letter_write 的幂等语义尚未实现，不能只在客户端注入。
 *   读工具（breath、pulse 等）不注入，避免无意义的参数噪音。
 * - 注入采用「强制覆盖」：即使模型自己传了 client_write_context，也以
 *   客户端计算的值为准，不信任模型生成的内容。
 * - 没有 client_write_context 的调用方（其他 MCP 客户端）在服务端
 *   完全走旧行为，不受影响。
 */
data class MemoryWriteContext(
    val client: String,
    val conversationId: String,
    val anchorNodeId: String,
    val generationId: String,
    val rerollGeneration: Int,
)

/**
 * 判断一个 MCP server 是否应注入写入上下文。
 *
 * 不再使用显示名/URL 启发式（可能误注入到非 Ombre 的 server）——
 * 必须由用户在 MCP Server 配置中显式开启 memoryWriteContextInjection。
 */
private val MEMORY_WRITE_TOOLS = setOf("hold", "grow")

/**
 * 计算当前这次生成对应的稳定锚点。
 *
 * 锚点规则（与 reroll 语义一一对应）：
 * 1. 用户消息处 reroll：会话已截断到该用户消息节点，锚点 = 该节点。
 * 2. assistant 消息处 reroll：messageRange 截掉该 assistant 消息，
 *    锚点 = 范围内最后一个 USER 消息节点。
 * 3. 普通发消息：锚点 = 新追加的用户消息节点（也是范围内最后一个 USER）。
 * 4. 异常情况（没有 USER 节点）：返回 null，调用方降级为不注入。
 */
fun resolveAnchorNodeId(
    conversation: Conversation,
    messageRange: ClosedRange<Int>?,
): Uuid? {
    val nodes = conversation.messageNodes
    if (nodes.isEmpty()) return null

    val end = (messageRange?.endInclusive?.plus(1))?.coerceIn(0, nodes.size) ?: nodes.size
    val start = messageRange?.start?.coerceIn(0, end) ?: 0
    if (start >= end) return null

    return nodes.subList(start, end)
        .lastOrNull { node -> node.role == MessageRole.USER }
        ?.id
}

/**
 * 计算 reroll 代数（0-based）。
 *
 * assistant 消息节点的 messages 长度 = 该节点已有分支数；本次生成将再
 * 追加一条，所以本次是第 (size) 次生成，0-based 为 size - 1。
 * 用户消息节点几乎不会 reroll，正常/用户消息 reroll 场景记为 0。
 */
fun resolveRerollGeneration(
    conversation: Conversation,
    messageRange: ClosedRange<Int>?,
): Int {
    if (messageRange == null) return 0
    // assistant reroll 时 messageRange 截到该 assistant 消息之前
    // （0 until nodeIndex），目标 assistant 节点在 endInclusive + 1。
    val targetIndex = messageRange.endInclusive + 1
    val node = conversation.messageNodes.getOrNull(targetIndex) ?: return 0
    if (node.role != MessageRole.ASSISTANT) return 0
    return (node.messages.size - 1).coerceAtLeast(0)
}

/**
 * 构造本次生成的 MemoryWriteContext；无法解析锚点时返回 null（不注入）。
 */
fun buildMemoryWriteContext(
    conversation: Conversation,
    messageRange: ClosedRange<Int>?,
    generationId: Uuid = Uuid.random(),
): MemoryWriteContext? {
    val anchorNodeId = resolveAnchorNodeId(conversation, messageRange) ?: return null
    return MemoryWriteContext(
        client = "rikkahub",
        conversationId = conversation.id.toString(),
        anchorNodeId = anchorNodeId.toString(),
        generationId = generationId.toString(),
        rerollGeneration = resolveRerollGeneration(conversation, messageRange),
    )
}

/**
 * 在工具参数上注入 client_write_context（仅对开启注入的 Ombre-Brain
 * 写入型工具）。
 *
 * 模型自己填写的同名参数会被强制覆盖 —— 锚点必须以客户端实际状态为准。
 *
 * @param injectionEnabled 该 MCP Server 是否显式开启注入
 *   （McpCommonOptions.memoryWriteContextInjection）。默认 false，不凭
 *   显示名/URL 猜测，避免误注入到非 Ombre 的普通 Server。
 */
fun injectMemoryWriteContext(
    raw: JsonObject,
    toolName: String,
    context: MemoryWriteContext?,
    injectionEnabled: Boolean,
): JsonObject {
    if (context == null) return raw
    if (!injectionEnabled) return raw
    if (toolName !in MEMORY_WRITE_TOOLS) return raw

    val merged = raw.toMutableMap()
    merged["client_write_context"] = buildJsonObject {
        put("client", JsonPrimitive(context.client))
        put("conversation_id", JsonPrimitive(context.conversationId))
        put("anchor_node_id", JsonPrimitive(context.anchorNodeId))
        put("generation_id", JsonPrimitive(context.generationId))
        put("reroll_generation", JsonPrimitive(context.rerollGeneration))
    }
    return JsonObject(merged)
}
