# AI Chat

基于 DeepSeek 的 Minecraft 服务端聊天模组。AI 以可配置前缀参与聊天，并按需读取知识库文件。

## 环境要求

- Minecraft 26.1.2
- Fabric Loader 0.19.4+
- Fabric API 0.155.3+26.1.2
- Java 25

## 构建

需要 JDK 25，并确保 `JAVA_HOME` 指向该 JDK。

```bash
./gradlew build
```

Windows 下使用：

```bat
gradlew.bat build
```

构建产物位于 `build/libs/aichat-<version>.jar`。

项目使用 Gradle Wrapper 9.7.1；Loom 1.18.4 要求 Gradle 9.7 或更高版本。

## 安装

1. 将模组 jar 与匹配 26.1.2 的 Fabric API 放入服务端 `mods/` 目录。
2. 启动服务端。首次启动会在 `config/aichat/` 生成：
   - `config.json`
   - `system_prompt.txt`
   - `persona_prompt.txt`
   - `knowledge/`
3. 在 `config.json` 中填写 `api_key`，重启服务端生效。修改配置无需重新构建。

## 配置

| 字段 | 说明 |
|---|---|
| `api_url` | Chat Completions 端点；也可只填 base URL（如 `https://xxx/v1`），模组会自动补 `/chat/completions` |
| `api_key` | DeepSeek API Key；保持占位值时模组跳过请求 |
| `model` | 模型名称，默认 `deepseek-flash` |
| `system_prompt_file` | 系统提示词文件名，相对 `config/aichat/` |
| `persona_prompt_file` | 人设提示词文件名，相对 `config/aichat/` |
| `reply_prefix` | AI 消息前缀，设为空字符串则不加前缀 |
| `max_context_messages` | 随请求发送的最近对话条数（玩家事件与 AI 回复都计入），默认 10 |
| `request_timeout_seconds` | 单次 HTTP 请求整体超时 |
| `enable_join_quit_events` | 是否对加入/退出事件触发 AI |
| `knowledge_dir` | 知识库目录名，相对 `config/aichat/` |
| `use_json_mode` | 是否发送 `response_format=json_object` |
| `enable_thinking` | 是否开启深度思考（`thinking`），默认关闭 |
| `enable_tools` | 是否发送工具定义（知识库检索），默认关闭；开启时 `use_json_mode` 不生效 |
| `api_type` | `auto` / `openai` / `deepseek` / `anthropic`，默认按 `api_url` 自动判断 |
| `reasoning_effort` | 推理强度（`low` / `medium` / `high` 等），留空不发送 |
| `send_thinking_field` | 是否发送 `thinking` 字段，默认 `false`；开启后部分平台（如千问 MaaS）会不返回 `tool_calls` |
| `max_output_tokens` | 最大输出 token，`0` 表示不发送（Anthropic 默认 4096） |
| `thinking_budget_tokens` | Anthropic 思考预算，默认 1024 |
| `config_version` | 配置文件版本，升级时自动补齐新字段 |
| `buffer_enabled` | 是否启用消息缓冲（防抖合并，省 token），默认 `true` |
| `buffer_delay_seconds` | 最后一条消息后等待秒数，默认 5 |
| `buffer_max_messages` | 缓冲区最多消息条数，默认 10，满了立即发送 |
| `buffer_max_chars` | 缓冲区最多字符数，默认 500，满了立即发送 |

运行期状态保存在 `config/aichat/state.json`（全局开关、调试模式、禁止列表、玩家模式），
token 用量按行追加到 `config/aichat/usage.jsonl`，两者都可直接查看。

## 模型兼容性

模组支持两类协议，`api_type` 为 `auto` 时按 `api_url` 自动判断：

| 协议 | 端点 | 认证 |
|---|---|---|
| OpenAI 兼容 | `.../chat/completions` | `Authorization: Bearer` |
| Anthropic Messages | `.../v1/messages` | `x-api-key` |

`api_url` 可以只填 base URL，模组会自动补全端点。常见配置：

| 平台 | api_url | api_type |
|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `auto` |
| 通义千问（百炼） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `auto` |
| Kimi | `https://api.moonshot.cn/v1` | `auto` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `auto` |
| 豆包（火山方舟） | `https://ark.cn-beijing.volces.com/api/v3` | `auto` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `openai` |
| OpenRouter | `https://openrouter.ai/api/v1` | `auto` |
| Ollama / LM Studio | `http://localhost:11434/v1` | `auto`（Key 可留空） |
| Claude | `https://api.anthropic.com/v1/messages` | `anthropic` |

说明：

- 默认不发送 `thinking` 字段。部分平台（如千问 MaaS）收到关闭思考的参数后不会返回 `tool_calls`，因此需要工具调用时请保持 `send_thinking_field: false`。
- OpenAI 推理模型可用 `reasoning_effort` 控制强度。
- Anthropic 的思考开关同样由 `enable_thinking` 控制，预算由 `thinking_budget_tokens` 指定。
- OpenAI Responses API、Gemini 原生 `generateContent` 不在支持范围，请使用各自的 Chat Completions 兼容端点。

## 命令

所有玩家：

| 命令 | 说明 |
|---|---|
| `/aichat on` | 默认状态：消息发给 AI，也能看到 AI 的消息 |
| `/aichat off` | 消息不发给 AI，也看不到 AI 的消息 |
| `/aichat onlyread` | 消息不发给 AI，能看到 AI 的消息 |

需要管理员权限（权限等级 2，即 OP）：

| 命令 | 说明 |
|---|---|
| `/aichat token` | 显示最近 7 天 token 用量（按天汇总） |
| `/aichat token clear` | 清空统计并重新计算（原文件按时间戳备份） |
| `/aichat reload` | 热重载 `config.json` 和 `system.json` |
| `/aichat enable` / `/aichat disable` | 全局开启/关闭 AI 聊天 |
| `/aichat debug` | 切换聊天框内的 token 用量显示（仅管理员可见） |
| `/aichat ban <玩家>` | 该玩家的发言不再发给 AI；离线玩家也可操作 |
| `/aichat unban <玩家>` | 解除禁止 |
| `/aichat banlist` | 查看禁止列表 |

## 消息缓冲

默认开启。玩家发言后等待 `buffer_delay_seconds`（默认 5 秒）再请求 AI：

- 等待期间任何新事件（聊天、加入、退出）都会重置计时器并进入缓冲区；
- 缓冲区达到 `buffer_max_messages` 或 `buffer_max_chars` 时立即发送；
- 多条事件会合并成一条请求，减少 API 调用次数和 token 消耗；
- 被 ban、`off`、`onlyread` 的玩家事件在进入缓冲区前就会被拦截。

## 双模型模式（可选）

`system.json` 控制判断模型与回复模型分离，默认关闭。开启后：

1. judge 模型只看事件，返回 `{"action":"reply"}` 或 `{"action":"ignore"}`；
2. 需要回复时再调用 reply 模型生成内容，reply 模型可以独立配置知识库工具。

`system.json` 中的 `judge` / `reply` 可分别填写 `api_url`、`api_key`、`model`、`api_type`、`system_prompt_file`。
留空的字段会回退到 `config.json` 的同名配置。两份默认提示词为 `judge_prompt.txt` 和 `reply_prompt.txt`。

## 配置升级

`config.json` 与 `system.json` 都带版本号。模组启动或执行 `/aichat reload` 时，
若文件版本低于当前版本，会先备份为 `.v<版本>.bak`，再用默认模板补齐缺失字段，已有值不会被覆盖。

## 行为

- 玩家聊天会触发一次判断；模型返回 `{"action":"reply","content":"..."}` 时广播回复，返回 `{"action":"ignore"}` 时静默。
- 每次请求的 token 用量会写入服务端日志；`/aichat debug` 开启后，管理员还会在聊天框里看到灰色用量行。
- 消息在发给 AI 之前先经过模组端校验：被 ban、全局关闭、玩家 `off`/`onlyread` 时直接拦截，不会产生 API 请求。
- AI 广播会逐个玩家投递：`off` 玩家收不到，`onlyread` 和 `on` 玩家正常接收。
- 玩家加入时发送欢迎消息；退出默认忽略。可在 `system_prompt.txt` 中调整。
- `/say`、`/me` 等命令消息不会触发 AI。
- 模型可通过 `read_knowledge_file` 读取 `knowledge/` 下的文件；文件读取受白名单和路径校验限制，最多两轮请求。`enable_tools` 为 `false` 时不发送工具定义，模型不会读取知识库。
- `enable_thinking` 控制请求里的 `thinking` 字段，默认 `false`（关闭深度思考）。
- `/reload` 重新读取 `config.json`，失败时保留原配置并提示错误。
- 不创建假玩家，不执行游戏操作。

## 测试

1. 配置 API Key，启动服务端，确认日志出现 `AI Chat 已加载`。
2. 发送普通聊天，确认收到带前缀的回复。
3. 执行 `/say hi`，确认不触发 AI。
4. 玩家加入，确认收到欢迎消息。
5. 在 `knowledge/` 添加 `server_info.txt`，询问相关内容，确认模型读取文件并回答。

## 说明

- 聊天上下文窗口已记录，但尚未加入 API 请求。
- API Key 以明文保存在 `config/aichat/config.json`，请限制配置文件访问权限。
