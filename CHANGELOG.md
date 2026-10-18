# Changelog

## 1.4.0 - 2026-10-18

### Added

- 新增 Anthropic Messages 协议支持（`/v1/messages`、`x-api-key`、`tool_use` / `tool_result`）。
- 新增 `api_type` 配置（`auto` / `openai` / `deepseek` / `anthropic`），`auto` 按 `api_url` 判断。
- 新增 `reasoning_effort`、`max_output_tokens`、`thinking_budget_tokens`、`send_thinking_field` 配置。
- 本地推理服务（Ollama / LM Studio / vLLM）允许 Key 留空。
- 错误信息会带上 API 返回的 `error.message`，便于排查。

### Changed

- 代码拆分为 `AiClient`（入口）、`OpenAiClient`、`AnthropicClient`，替换 `DeepSeekClient`。
- `thinking` 字段改由 `send_thinking_field` 控制，默认不发送；部分平台关闭思考后不再返回 `tool_calls`。
- 工具调用与 usage 统计在两种协议下行为一致。

### Fixed

- `api_url` 支持 base URL（如 `https://xxx/v1`），自动补上 `/chat/completions`，修复配置 base URL 时请求返回 404。
- 启用工具调用时不再发送 `response_format=json_object`，修复部分 OpenAI 兼容平台（如千问 MaaS）在 JSON 模式下不返回 `tool_calls`、模型凭空编答案的问题。此时依靠系统提示词约束 JSON 输出。
- 默认不发送 `thinking` 字段，避免部分平台在关闭思考后无法调用工具。

## 1.3.0 - 2026-10-18

### Added

- `/reload` 热重载 `config.json`，同时保留原版数据包重载行为。
- `enable_thinking` 配置项，控制请求的 `thinking` 字段，默认关闭。
- `enable_tools` 配置项，控制是否发送知识库工具定义，默认关闭。

### Changed

- 深度思考与工具调用不再写死在代码里，改由配置控制。
- 配置加载失败时 `/reload` 会提示错误，旧配置保持不变。

## 1.2.0 - 2026-10-18

### Added

- 终端日志输出每次 AI 请求的 token 用量（prompt / completion / total）。
- token 用量记录到 `config/aichat/usage.jsonl`，`/aichat token` 查看最近 7 天按日汇总。
- `/aichat enable|disable` 全局开关 AI 聊天。
- `/aichat debug` 切换聊天框内的 token 用量显示（仅管理员可见）。
- `/aichat ban|unban|banlist` 管理不再触发 AI 的玩家。
- `/aichat on|off|onlyread` 玩家自行设置 AI 聊天状态，默认 `on`。
- 消息进入 AI 前在模组端校验，被拦截的消息不会产生 API 请求。
- 运行期状态持久化到 `config/aichat/state.json`。

### Changed

- AI 回复改为按玩家状态投递，`off` 玩家收不到 AI 消息。
- 版本号提升至 1.2.0。

## 1.1.0 - 2026-10-18

### Added

- 新增 `reply_prefix` 配置项，可自定义 AI 消息前缀。
- 新增 `persona_prompt.txt`，人设提示词改为独立文件。

### Changed

- `config.json` 不再保存人设文本，改用 `persona_prompt_file` 指定文件。
- 升级时自动将旧版 `persona_prompt` 字段迁移到 `persona_prompt.txt`，并从配置中移除。
- README 更新为通用构建与部署说明。

### Removed

- `config.json` 中的 `persona_prompt` 字段。

## 1.0.0 - 2026-10-18

- 首个版本：服务端聊天回复、加入欢迎、命令消息过滤、知识库工具调用。
