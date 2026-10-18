# AI Chat（Fabric 服务端模组）

让 DeepSeek 在 Minecraft 服务端聊天框里以 `[AI]` 前缀参与聊天，支持按需读取知识库文件。

- Minecraft：26.1.2
- Fabric Loader：0.19.4+（编译目标 0.19.4）
- Fabric API：0.155.3+26.1.2（编译目标）
- Java：25（服务端和构建 JVM 都需要）

## 构建

```powershell
$env:JAVA_HOME = "D:\E\JAVA\zuluJDK25\zulu25.34.17-ca-jdk25.0.3-win_x64"
.\gradlew.bat build
```

产物：`build/libs/aichat-1.0.0.jar`（`-sources.jar` 不需要部署）。

Gradle 使用 wrapper 9.7.1；Loom 1.18.4 要求 Gradle 9.7+。

## 部署

1. 把 `aichat-1.0.0.jar` 放进服务端 `mods/`。
2. 服务端 `mods/` 里同时要有对应 26.1.2 的 Fabric API。
3. 首次启动会生成 `config/aichat/`：
   - `config.json`：API Key、模型、超时、开关
   - `system_prompt.txt`：系统提示词（含 `{KNOWLEDGE_FILE_LIST}` / `{PERSONA_PROMPT}` 占位符）
   - `knowledge/`：知识库文件目录

修改配置或提示词后**不需要重新构建**，重启服务端即可生效。

## 配置字段

| 字段 | 说明 |
|---|---|
| `api_url` | DeepSeek Chat Completions 端点 |
| `api_key` | 自己的 DeepSeek Key；保持 `sk-your-key-here` 时模组会跳过请求 |
| `model` | 默认 `deepseek-flash`（当前可用模型另有 `deepseek-v4-pro`） |
| `system_prompt_file` | 系统提示词文件名，相对 `config/aichat/` |
| `persona_prompt` | 人设，仅影响语气和风格 |
| `max_context_messages` | 滚动窗口条数（第一版只维护、不拼进请求） |
| `request_timeout_seconds` | 单次 HTTP 请求整体超时 |
| `enable_join_quit_events` | 是否对加入/退出触发 AI |
| `knowledge_dir` | 知识库目录名，相对 `config/aichat/` |
| `use_json_mode` | 是否发送 `response_format=json_object`；若工具调用报 400 可改 `false` |

## 行为

- 玩家聊天 → 一次 AI 判断，返回 `{"action":"reply","content":"..."}` 才以 `[AI] ...` 广播；返回 `ignore` 静默。
- 加入 → 按当前提示词主动欢迎；退出 → 默认忽略（可在 `system_prompt.txt` 调整）。
- `/say`、`/me` 等命令消息会被过滤，不触发 AI。
- 知识库：模型可调用 `read_knowledge_file` 读取 `knowledge/` 下的文件；文件名白名单 + 路径越界校验，最多两轮请求。
- 不创建假玩家、不执行游戏操作、不读取 Key 以外的敏感信息；异常日志只记录状态码。

## 冒烟测试清单

1. 填好 Key，启动服务端，确认日志出现 `AI Chat 已加载`。
2. 普通聊天 → AI 回复带 `[AI]` 前缀。
3. `/say hi` → 不触发。
4. 玩家加入 → 收到欢迎语。
5. 在 `knowledge/` 放一个 `server_info.txt`，问相关内容 → 触发工具读取并回答。
6. 若知识库环节报 400 → 把 `use_json_mode` 改为 `false` 再试。

## 已知限制

- 上下文滚动窗口尚未接入请求（按规格第一版保留 TODO，代码位于 `AiDispatcher.RECENT`）。
- `response_format=json_object` 与工具调用的组合依赖模型兼容性，已提供配置开关。
- API Key 以明文保存在服务端 `config/aichat/config.json`，注意文件权限。
