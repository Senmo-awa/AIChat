# Changelog

## 1.5.4 - 2026-10-20

### Fixed

- 双模型模式下，判断模型（judge）现在也会收到最近的历史记录。此前 judge 只拿到当前事件，`judge_prompt.txt` 中依赖上下文的规则（如“玩家在和其他人聊天时不要插话”“玩家表现出不耐烦就停止参与”）无法生效，表现和单模型模式不一致。
- 判断模型返回空内容时会打印警告，不再静默按“不回复”处理。

### Changed

- debug 模式新增 `[debug] 判断模型结论：reply/ignore（事件：…，历史条数：…）`，可以直接看到 judge 的判定。
- 默认 `judge_prompt.txt` 补充历史记录的用途说明。

## 1.5.3 - 2026-10-18

### Changed

- `/aichat debug` 现在除了聊天框 token 用量，还会把 AI 接口返回的**完整原始 JSON**（`choices`、`tool_calls`、`usage`、`finish_reason` 等）和模组的解析结果写进服务端日志，方便排查「AI 不回复」到底卡在哪一层。

## 1.5.2 - 2026-10-18

### Fixed

- 单模型模式不再要求返回值必须带 `action`：只要 JSON 里有非空的 `content` 就按回复处理。此前自定义提示词只写 `{"content":"..."}` 时，回复会被静默丢弃（不再生成任何消息，也不打警告）。
- 解析器会先剥掉 ```json 代码块和 JSON 前后的多余文字，再做解析。
- `content` 返回成 `[{type,text}]` 数组的兼容端点现在能正确取到文本。
- 丢弃回复时会在日志里打印截断后的原始返回，便于排查。

### Added

- 添加 MIT 许可证（`LICENSE`），README 增加许可证说明。

## 1.5.1 - 2026-10-18

### Fixed

- 上下文记忆真正生效：`max_context_messages` 条最近的玩家事件与 AI 回复会作为历史消息随请求发送，AI 可以记住前几句聊天。
- 上下文在 worker 线程内串行维护，多个缓冲批次排队时顺序不会错乱。
- 更新默认 `system_prompt.txt` 和 `reply_prompt.txt`，说明历史记录只用于理解上下文，只需回应最后一批事件。

## 1.5.0 - 2026-10-18

### Added

- 消息缓冲：最后一条消息后等待 5 秒再请求 AI，期间的新事件重置计时器并合并发送；默认 10 条 / 500 字符上限，满则立即发送。相关参数可在 `config.json` 配置。
- 双模型模式：`system.json` 中可分别配置判断模型（judge）和回复模型（reply），默认关闭；两套 API 地址、Key、模型互相独立，留空时回退到 `config.json`。
- 新增默认提示词 `judge_prompt.txt`（只判断是否回复）和 `reply_prompt.txt`（生成回复）。
- `/aichat token clear`：清空 token 统计并重新计算，旧文件按时间戳备份。
- `/aichat reload`：热重载 `config.json` 与 `system.json`。
- `config.json` / `system.json` 增加版本号，升级时自动备份并补齐缺失字段。

### Changed

- `/reload` 改为 `/aichat reload`，不再覆盖原版 `/reload` 命令。
- 协议层改为由 `ApiProfile` 驱动，供单模型与双模型共用。

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
