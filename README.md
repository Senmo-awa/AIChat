# AI Chat

Minecraft 服务端聊天模组，支持 OpenAI 兼容与 Anthropic Messages 两类 API。AI 以可配置前缀参与聊天，可按需读取知识库，并支持消息缓冲、上下文记忆与双模型模式。

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
   - `config.json`、`system.json`
   - `system_prompt.txt`、`persona_prompt.txt`
   - `judge_prompt.txt`、`reply_prompt.txt`
   - `knowledge/`
3. 在 `config.json` 中填写 `api_key`（双模型模式则填写 `system.json`），重启服务端或执行 `/aichat reload` 生效。修改配置无需重新构建。

## 配置

| 字段 | 说明 |
|---|---|
| `api_url` | Chat Completions 端点；也可只填 base URL（如 `https://xxx/v1`），模组会自动补 `/chat/completions` |
| `api_key` | API Key；保持占位值时模组跳过请求（本地服务可留空） |
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
| `/aichat debug` | 切换调试模式：聊天框显示 token 用量（仅管理员），同时把 AI 接口的原始响应和解析结果写进服务端日志 |
| `/aichat ban <玩家>` | 该玩家的发言不再发给 AI；离线玩家也可操作 |
| `/aichat unban <玩家>` | 解除禁止 |
| `/aichat banlist` | 查看禁止列表 |

## 消息缓冲

默认开启。玩家发言后等待 `buffer_delay_seconds`（默认 5 秒）再请求 AI：

- 等待期间任何新事件（聊天、加入、退出）都会重置计时器并进入缓冲区；
- 缓冲区达到 `buffer_max_messages` 或 `buffer_max_chars` 时立即发送；
- 多条事件会合并成一条请求，减少 API 调用次数和 token 消耗；
- 被 ban、`off`、`onlyread` 的玩家事件在进入缓冲区前就会被拦截。

## 双模型模式（可选，默认关闭）

作用：先用一个便宜、快速的模型判断“要不要回复”，需要回复时再调用另一个模型生成内容。
两套 API 完全独立，可以指向不同平台、使用不同 Key 和模型。

### 开启步骤

1. 打开 `config/aichat/system.json`。
2. 把 `"dual_model_enabled": false` 改成 `true`。
3. 填写 `judge`（判断是否回复）和 `reply`（生成回复）两套配置。
4. 执行 `/aichat reload`，或重启服务端。
5. 用 `/aichat token` 观察用量；终端日志会分别记录两个模型的请求。

### 配置示例

```json
{
  "version": 1,
  "dual_model_enabled": true,
  "judge": {
    "api_url": "https://api.deepseek.com",
    "api_key": "sk-judge-key",
    "model": "deepseek-flash",
    "api_type": "auto",
    "system_prompt_file": "judge_prompt.txt",
    "max_output_tokens": 256,
    "thinking_budget_tokens": 1024
  },
  "reply": {
    "api_url": "https://api.moonshot.cn/v1",
    "api_key": "sk-reply-key",
    "model": "kimi-k3",
    "api_type": "auto",
    "system_prompt_file": "reply_prompt.txt",
    "max_output_tokens": 0,
    "thinking_budget_tokens": 1024
  }
}
```

### 说明

- `judge` 只返回 `{"action":"reply"}` 或 `{"action":"ignore"}`，不发送工具定义，适合用便宜的小模型。
- `reply` 只在需要回复时调用，负责生成内容；是否发送知识库工具由 `config.json` 的 `enable_tools` 控制。
- `api_url` / `api_key` / `model` / `api_type` 留空时自动回退到 `config.json` 的同名配置，因此可以只填一套先跑通。
- 两份默认提示词是 `judge_prompt.txt` 和 `reply_prompt.txt`，可以直接编辑。
- 判断模型只看当前事件；回复模型会收到最近 `max_context_messages` 条历史。
- 关闭双模型模式只需把 `dual_model_enabled` 改回 `false`，然后执行 `/aichat reload`。

## 配置升级

`config.json` 与 `system.json` 都带版本号。模组启动或执行 `/aichat reload` 时，
若文件版本低于当前版本，会先备份为 `.v<版本>.bak`，再用默认模板补齐缺失字段，已有值不会被覆盖。

## 行为

- 玩家聊天会触发一次判断；模型返回 `{"action":"reply","content":"..."}` 或只返回 `{"content":"..."}` 时广播回复，返回 `{"action":"ignore"}` 时静默。返回值带 ```json 代码块或前后有少量文字也能正确解析。
- 最近 `max_context_messages` 条玩家事件与 AI 回复会作为历史消息随请求发送，AI 可以记住前几句聊天。
- 每次请求的 token 用量会写入服务端日志；`/aichat debug` 开启后，管理员还会在聊天框里看到灰色用量行，同时服务端日志会额外输出：
  - `[debug] OpenAI 兼容响应 HTTP 200：{...}` / `[debug] Anthropic 响应 HTTP 200：{...}`：接口返回的完整 JSON（含 `choices`、`tool_calls`、`usage`、`finish_reason` 等原始字段）
  - `[debug] 解析结果：reply=...，content=...（事件：...，消息数：...，历史条数：...）`：模组最终解析出的结果

  排查「AI 不回复」时，先看这两行：如果原始响应里有内容但解析结果是 `reply=false`，问题在解析或提示词；如果原始响应本身没有内容，问题在模型或平台。
- 消息在发给 AI 之前先经过模组端校验：被 ban、全局关闭、玩家 `off`/`onlyread` 时直接拦截，不会产生 API 请求。
- AI 广播会逐个玩家投递：`off` 玩家收不到，`onlyread` 和 `on` 玩家正常接收。
- 玩家加入时发送欢迎消息；退出默认忽略。可在 `system_prompt.txt` 中调整。
- `/say`、`/me` 等命令消息不会触发 AI。
- 模型可通过 `read_knowledge_file` 读取 `knowledge/` 下的文件；文件读取受白名单和路径校验限制，最多两轮请求。`enable_tools` 为 `false` 时不发送工具定义，模型不会读取知识库。
- `/aichat reload` 重新读取 `config.json` 与 `system.json`，失败时保留原配置并提示错误。
- 不创建假玩家，不执行游戏操作。

## 测试

1. 配置 API Key，启动服务端，确认日志出现 `AI Chat 已加载`。
2. 发送普通聊天，确认收到带前缀的回复。
3. 连续对话两句，确认 AI 能引用上一句（上下文记忆）。
4. 执行 `/say hi`，确认不触发 AI。
5. 玩家加入，确认收到欢迎消息。
6. 在 `knowledge/` 添加 `server_info.txt`，询问相关内容，确认模型读取文件并回答。
7. 开启双模型模式后再次对话，确认 judge 与 reply 的 token 都记录在日志中。

## 说明

- 上下文条数由 `max_context_messages` 控制，调大能记住更多内容，但每次请求的 prompt token 也会增加。
- API Key 以明文保存在 `config/aichat/config.json` 与 `system.json`，请限制配置文件访问权限。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。你可以自由使用、修改、分发，甚至用于商业项目，只需保留版权声明。

