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
| `api_url` | DeepSeek Chat Completions 端点 |
| `api_key` | DeepSeek API Key；保持占位值时模组跳过请求 |
| `model` | 模型名称，默认 `deepseek-flash` |
| `system_prompt_file` | 系统提示词文件名，相对 `config/aichat/` |
| `persona_prompt_file` | 人设提示词文件名，相对 `config/aichat/` |
| `reply_prefix` | AI 消息前缀，设为空字符串则不加前缀 |
| `max_context_messages` | 保留的聊天上下文条数（当前仅记录，不发送） |
| `request_timeout_seconds` | 单次 HTTP 请求整体超时 |
| `enable_join_quit_events` | 是否对加入/退出事件触发 AI |
| `knowledge_dir` | 知识库目录名，相对 `config/aichat/` |
| `use_json_mode` | 是否发送 `response_format=json_object` |

运行期状态保存在 `config/aichat/state.json`（全局开关、调试模式、禁止列表、玩家模式），
token 用量按行追加到 `config/aichat/usage.jsonl`，两者都可直接查看。

## 命令

所有玩家：

| 命令 | 说明 |
|---|---|
| `/aichat on` | 默认状态：消息发给 AI，也能看到 AI 的消息 |
| `/aichat off` | 消息不发给 AI，也看不到 AI 的消息 |
| `/aichat onlyread` | 能看见 AI 的消息，但 AI 不回应 |

需要管理员权限（权限等级 2，即 OP）：

| 命令 | 说明 |
|---|---|
| `/aichat token` | 显示最近 7 天 token 用量（按天汇总） |
| `/aichat enable` / `/aichat disable` | 全局开启/关闭 AI 聊天 |
| `/aichat debug` | 切换聊天框内的 token 用量显示（仅管理员可见） |
| `/aichat ban <玩家>` | 该玩家的发言不再发给 AI；离线玩家也可操作 |
| `/aichat unban <玩家>` | 解除禁止 |
| `/aichat banlist` | 查看禁止列表 |

## 行为

- 玩家聊天会触发一次判断；模型返回 `{"action":"reply","content":"..."}` 时广播回复，返回 `{"action":"ignore"}` 时静默。
- 每次请求的 token 用量会写入服务端日志；`/aichat debug` 开启后，管理员还会在聊天框里看到灰色用量行。
- 消息在发给 AI 之前先经过模组端校验：被 ban、全局关闭、玩家 `off`/`onlyread` 时直接拦截，不会产生 API 请求。
- AI 广播会逐个玩家投递：`off` 玩家收不到，`onlyread` 和 `on` 玩家正常接收。
- 玩家加入时发送欢迎消息；退出默认忽略。可在 `system_prompt.txt` 中调整。
- `/say`、`/me` 等命令消息不会触发 AI。
- 模型可通过 `read_knowledge_file` 读取 `knowledge/` 下的文件；文件读取受白名单和路径校验限制，最多两轮请求。
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
