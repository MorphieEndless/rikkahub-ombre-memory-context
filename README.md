# rikkahub-ombre-memory-context

RikkaHub 适配 Ombre-Brain 的 memory write context 注入补丁验证仓库。

## 这是什么

RikkaHub 以「单条对话为截止点 reroll」时，上一分支已执行的 `hold/grow` 在 Ombre-Brain 里是永久副作用；重新生成会再写一份，导致记忆库重复桶堆积。

本补丁让 RikkaHub 给 Ombre-Brain 的写入型工具（`hold`/`grow`）注入稳定的 `client_write_context`（conversation_id + anchor_node_id + generation_id），服务端据此做幂等去重。

## 文件

- `rikkahub-memory-context.patch` — 补丁本体（4 个文件：McpConfig.kt / MemoryWriteContext.kt / ChatService.kt / MemoryWriteContextTest.kt）
- `.github/workflows/verify-memory-context.yml` — GitHub Actions 验证流水线：检出官方 `rikkahub/rikkahub` master → apply 补丁 → compileDebugKotlin → MemoryWriteContextTest → assembleDebug → 上传 APK

## 验证方式

push 到 main 或手动 workflow_dispatch 触发。Actions 会在官方源码上打补丁并完整编译验证。

## 验证记录

- 2026-08-26：workflow v2（官方源码 + apply 补丁方案）就绪，等待首次运行。

## 设计原则

- 默认关闭：不凭显示名/URL 猜测，必须由用户在 MCP Server 配置中显式开启 `memoryWriteContextInjection`
- 只注入 `hold`/`grow`（服务端已实现 receipt 幂等的工具）；`plan`/`letter_write` 不注入
- 注入采用强制覆盖：即使模型自己传了同名参数，也以客户端计算的锚点为准
