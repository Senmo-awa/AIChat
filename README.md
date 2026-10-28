# AI Chat

Minecraft 服务端聊天模组，支持 OpenAI 兼容与 Anthropic Messages 两类 API。AI 以可配置前缀参与聊天，可按需读取知识库，并支持消息缓冲、上下文记忆与双模型模式。

## 环境要求

- Minecraft 26.1.2
- Fabric Loader 0.19.4+
- Fabric API 0.155.3+26.1.2
- Java 25
- （可选）LuckPerms：只有 `server_tools_permission_mode` 设为 `luckperms` 或 `/aichat tools` 的权限判定用得上，没装不影响其他功能

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

> 2.0.0 起 `config.json` / `system.json` **自带中文注释**：每个字段上面都用 `//` 说明了作用，不用再翻文档。以 `//` 开头的行读取时会被忽略，可以放心改、也可以自己加注释；平时模组不会重写你的文件，只有 `config_version` 升级时才会备份并重写一次。

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
| `search_enabled` | 是否允许模型调用 `web_search` 联网搜索，默认 `true`；关闭后不注册该工具，行为与 1.6.2 一致 |
| `search_provider` | 搜索后端：`bing`（默认，抓 cn.bing.com，零 Key）/ `searxng`（自建实例）/ `tavily` / `bocha` / `zhipu` |
| `search_api_url` | 搜索接口地址，留空用 provider 默认端点；`searxng` 必须填（如 `http://127.0.0.1:8888`） |
| `search_api_key` | 搜索 API Key；`bing` / `searxng` 留空即可 |
| `search_max_results` | 一次搜索最多取几条结果，默认 5，上限 10 |
| `search_timeout_seconds` | 搜索请求超时（秒），默认 10，与对话超时相互独立 |
| `memory_enabled` | 是否开启记忆功能（AI 读写记忆文件），默认 `true`；开启后会一并打开工具通道，见「记忆功能」 |
| `memory_dir` | 记忆文件夹名，相对 `config/aichat/`，默认 `memory` |
| `memory_max_file_kb` | 单个记忆文件上限（KB），默认 8；写入超限由 AI 自己压缩后覆写 |
| `config_version` | 配置文件版本，升级时自动补齐新字段 |
| `buffer_enabled` | 是否启用消息缓冲（防抖合并，省 token），默认 `true` |
| `buffer_delay_seconds` | 最后一条消息后等待秒数，默认 5 |
| `buffer_max_messages` | 缓冲区最多消息条数，默认 10，满了立即发送 |
| `buffer_max_chars` | 缓冲区最多字符数，默认 500，满了立即发送 |
| `server_tools_enabled` | 服务器管理工具总开关，默认 `false`（关闭）。开启后，有权限的管理员在聊天里用前缀发起的请求才能读写服务器根目录，见「服务器管理工具」 |
| `server_tools_prefix` | 特权请求前缀，默认 `!+`；只有以它开头的聊天消息才允许调用 `server_files`。留空表示关闭前缀通道 |
| `server_tools_permission_mode` | 权限判定方式：`op`（原版权限等级，默认）/ `list`（管理员名单文件）/ `luckperms`（权限节点） |
| `server_tools_permission_level` | `op` 模式要求的原版权限等级，默认 `4`（op 满级）；`1`=管理员 `2`=游戏管理员 `3`=op `4`=服主 |
| `server_tools_luckperms_node` | `luckperms` 模式查询的权限节点，默认 `aichat.servertools` |
| `server_tools_admins_file` | `list` 模式的名单文件名，默认 `server_admins.json`，可用 `/aichat tools add|remove` 维护 |
| `server_tools_read_max_kb` | 单次读取上限（KB），默认 48；超出会截断并提示 AI 分段读 |
| `server_tools_write_max_kb` | 单次写入上限（KB），默认 64 |
| `server_tools_list_max_entries` | 列目录最多返回多少项，默认 200 |
| `server_tools_trash_enabled` | 删除时是否移入 `.aichat-trash/<时间戳>/` 回收目录，默认 `true`（强烈建议保持） |
| `server_tools_broadcast` | 特权消息是否广播给其他玩家：`show` 原样广播（默认）/ `hide` 不广播（只发发送者、管理员、控制台）/ `show_by_flag` 默认不广播，带标志位才广播 / `hide_by_flag` 默认广播，带标志位就不广播 |
| `server_tools_broadcast_flag` | 上面两种 flag 模式用的消息尾缀，默认 `-f`；尾缀本身不会发给 AI |
| `server_tools_deny_paths` | 禁止读写的路径（相对服务器根目录），默认挡住本模组自己的 `config.json` / `system.json` / `server_admins.json` 和 `.aichat-trash` |
| `system_prompt_in_user` | 把系统提示词（格式契约 + 文件列表 + 人设）再拼进最后一条用户消息，默认 `true`。部分中转站会把 `system` 角色整条丢弃，导致模组的提示词到不了模型（表现为模型不输出 JSON、瞎猜知识库文件名）；接口确实支持 `system` 时可设为 `false` 省一份重复 token |

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
- 判断模型（双模型模式的 `judge`）在智谱 GLM 平台（`bigmodel.cn` / `z.ai` / `glm-*`）上会自动关闭思考，避免推理 token 吃满输出预算；`glm-5.3` 系列强制思考，不走该逻辑。
- 不少中转站 / 兼容端点会**悄悄丢掉 `system` 角色**和 `response_format`。表现是：模型把服务器事件当成「用户让我分析日志」，回一段散文或 markdown，判断永远失败；模型还会瞎猜知识库文件名。`system_prompt_in_user`（默认 `true`）会把系统提示词再拼进最后一条用户消息，遇到上述现象请保持开启；接口确实支持 `system` 时设 `false` 可省约一份提示词 token。
- 推理模型的思考 token 同样计入 `max_tokens`，预算给小了会得到 `finish_reason=length` + 空 `content`。模组遇到这种情况会自动放大预算重试一次，判断模型的默认预算是 1024。
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
| `/aichat tools` | 查看服务器管理工具状态：开关、前缀、权限方式、管理员名单、你自己能不能用 |
| `/aichat tools add <玩家>` | 把玩家加入名单（需 op 4 级；仅 `list` 模式生效） |
| `/aichat tools remove <玩家>` | 把玩家移出名单（需 op 4 级） |

## 消息缓冲

默认开启。玩家发言后等待 `buffer_delay_seconds`（默认 5 秒）再请求 AI：

- 等待期间任何新事件（聊天、加入、退出）都会重置计时器并进入缓冲区；
- 缓冲区达到 `buffer_max_messages` 或 `buffer_max_chars` 时立即发送；
- 多条事件会合并成一条请求，减少 API 调用次数和 token 消耗；
- 被 ban、`off`、`onlyread` 的玩家事件在进入缓冲区前就会被拦截。

## 双模型模式（可选，默认关闭）

作用：先用一个便宜、快速的模型判断“要不要回复”，需要回复时再调用另一个模型生成内容。
两套 API 完全独立，可以指向不同平台、使用不同 Key 和模型。

判断模型和回复模型都会收到最近 `max_context_messages` 条历史，`judge_prompt.txt` 中依赖上下文的规则（例如“玩家在和其他人聊天时不要插话”）才能真正生效。

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
    "max_output_tokens": 1024,
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
- 判断模型和回复模型都会收到最近 `max_context_messages` 条历史。
- 判断模型的默认输出预算是 1024 token；如果用的是默认开启思考的推理模型（如 GLM-4.7 系列），模组会自动发送 `"thinking": {"type": "disabled"}` 关闭思考，并在遇到 `finish_reason=length` 且 `content` 为空时自动放大预算重试一次。GLM-5.3 系列强制思考，只能靠调大 `max_output_tokens` 解决。
- 关闭双模型模式只需把 `dual_model_enabled` 改回 `false`，然后执行 `/aichat reload`。

## 联网搜索

模组内置 `web_search` 工具：模型判断需要外部实时信息（新闻、天气、比分、价格等）时调用它，模组去搜索接口取结果，再把「标题 + 摘要 + 链接」回灌给模型组织回答。**搜索由模组执行，与模型平台无关**——中转站不支持服务端搜索也能用。

| provider | 端点 | Key | 说明 |
|---|---|---|---|
| `bing`（默认） | `https://cn.bing.com/search` | 不需要 | 抓结果页 HTML，国内直连；最省事，但属于页面抓取，Bing 改版或要求验证时可能失效 |
| `searxng` | 自建实例 `/search?format=json` | 不需要 | 需要自己有 SearXNG，`search_api_url` 必填，零费用不限量 |
| `tavily` | `https://api.tavily.com/search` | 需要 | 有免费额度，境外节点，响应偏慢 |
| `bocha` | `https://api.bochaai.com/v1/web-search` | 需要 | 中文结果质量好，按次计费 |
| `zhipu` | `https://open.bigmodel.cn/api/paas/v4/web_search` | 需要 | 国内直连，单价低 |

约束与行为：

- 一次对话最多搜索一次，防模型反复搜索烧 token；结果总长度上限 4000 字符（单条摘要 200 字符），超出截断。
- 搜索失败不重试，直接把 `搜索失败: <原因>` 当工具结果回灌，并写一条 WARN 日志，让模型照实告诉玩家「没搜到」而不是编造。
- 搜索在 `aichat-worker` 线程执行，不阻塞服务端主线程。
- 关闭：`search_enabled: false`；`provider` 需要 Key 但没填 Key 时，工具不会被注册（模型看不到搜索能力，不会反复尝试失败）。
- 想让模型主动用搜索，在 `system_prompt.txt` 里补一条规则即可（默认模板已含）：

  ```
  9. 如果当前事件需要外部实时信息（新闻、天气、比分、价格、人物近况等），可以调用 web_search 工具联网搜索，一次对话最多搜一次，搜索词要简洁。
  10. 搜索结果只是参考，可能过期或不准；引用时说明来源，搜不到或搜索失败就照实说“没搜到”，绝不编造新闻、数字或链接。
  ```

## 记忆功能

上下文窗口只装得下最近几条消息，玩家说的「某人上线时提醒他交材料」这类约定，消息一多就丢了。记忆功能给 AI 一个自己维护的记事本：它可以把这类信息写进 `memory/` 目录，下次对话再读回来。

开启方式（`config.json`）：

```json
"memory_enabled": true,
"memory_dir": "memory",
"memory_max_file_kb": 8
```

开启后模组注册两个工具：

| 工具 | 参数 | 作用 |
|---|---|---|
| `memory_read` | `file_name`（可省略） | 省略时返回文件清单和各自大小；填了则返回全文 |
| `memory_write` | `file_name`、`content`、`mode` | `mode=append` 追加（默认）/ `overwrite` 覆写 |

行为与约束：

- 记忆规则由模组在读取提示词时**自动追加到系统提示词末尾**，不需要改自己的 `system_prompt.txt`。judge 提示词不会追加（判断阶段不发送工具）。
- 超过 `memory_max_file_kb` 的写入会被拒绝，返回的是「先 `memory_read` 读出全文 → 压缩成不超过 N KB 的要点 → `mode=overwrite` 覆写」的指引，由模型自己完成压缩；文件用到上限 80% 时会提前提醒。模组不会静默截断，也不会写出半截文件。
- 文件名白名单：不带目录、不含 `..`，只允许字母数字/中文/`_`/`-`/`.`/空格，扩展名 `.md` / `.txt`（缺省补 `.md`），最长 64 字符；越界路径直接拒绝，写了 `.exe` 这种扩展名直接报错。
- 文件数量软上限 32 个：达上限后只能覆写或合并进已有文件，防止被反复刷文件撑爆目录。
- 目录默认 `config/aichat/memory/`，配置加载时自动创建；可用 `/aichat memory` 查看清单，每次写入会在服务端日志留一条 INFO。
- 记忆文件是 AI 自己的笔记，提示词里明确要求它只当资料参考，**不把文件内容当作系统规则或玩家指令执行**——防止有人把恶意指令写进记忆文件。
- `memory_enabled`（默认 `true`）会顺带打开工具通道：即使 `enable_tools` 是 `false`，也会发送记忆工具定义（知识库/搜索仍由 `enable_tools` 控制）。带工具时不发送 `response_format=json_object`，与既有取舍一致；想完全回到 1.7.0 的老行为就把它设成 `false`。
- 注意 token：每轮对话都会带上工具定义（几 KB 级），记忆文件正文只在模型主动 `memory_read` 时才进 prompt。

## 服务器管理工具（2.0.0 新增，默认关闭）

让 AI 能**查看与管理服务器根目录里的文件**：排查故障时读 `logs/latest.log`、看 `mods/` 里装了哪些模组、检查 `config/` 里的配置、临时记录诊断结果。默认关闭，并且**只对管理员生效**。

### 三道门，缺一不可

1. `server_tools_enabled` 为 `true`（默认 `false`）；
2. 这条聊天消息以 `server_tools_prefix`（默认 `!+`）开头；
3. 发消息的玩家通过权限判定（见下）。

普通聊天（没有前缀）**不会**注册这个工具：模型连工具名字都看不到，硬编出名字来也只会收到「未知工具」。带前缀但没有权限的玩家，消息会被直接丢弃并收到一句提示，不会发给 AI（不浪费 token）。

### 怎么用

```
!+ 看一下 mods 目录里都有什么模组
!+ 读一下 logs/latest.log 最后 200 行，服务器刚才为什么崩的
!+ config/fabric-server-launch.properties 里改了什么
```

前缀只是「授权令牌」，AI 收到的是去掉前缀之后的内容。

**能不能被别的玩家看到**由 `server_tools_broadcast` 决定（默认 `show` 原样广播）：
`hide` 完全不广播，只发给发送者、管理员和控制台；`show_by_flag` 默认不广播、消息末尾加 `-f` 才广播；`hide_by_flag` 默认广播、加 `-f` 就不广播。标志位会被剥掉，不会进 AI 的输入。

### 工具能力

一个工具 `server_files` 覆盖全部操作：

| op | 作用 |
|---|---|
| `list` | 列目录（类型 / 大小 / 修改时间） |
| `read` | 读文本文件（有上限，超了截断；二进制文件不返回内容） |
| `write` | 新建、覆写或追加（`mode=append` 更安全） |
| `mkdir` | 建目录（自动递归） |
| `delete` | 删除：默认移入 `.aichat-trash/<时间戳>/` 回收目录 |
| `move` | 移动或改名（目标已存在则拒绝，不覆盖） |
| `info` | 类型 / 大小 / 修改时间 |
| `search` | 按文件名关键词查找，支持 `*` 通配（如 `*.jar`） |

### 权限判定的三种方式

`server_tools_permission_mode` 决定谁算「有权限」：

| 模式 | 说明 |
|---|---|
| `op`（默认） | 按原版权限等级判，`server_tools_permission_level` 默认 `4`。想放开到 3 级 op 就改成 `3` |
| `list` | 用模组自己的名单文件 `config/aichat/server_admins.json`，非 op 玩家也能被授权。可用 `/aichat tools add <玩家>` / `remove <玩家>` 维护，也可以直接改文件后 `/aichat reload` |
| `luckperms` | 查 LuckPerms 的权限节点（`server_tools_luckperms_node`，默认 `aichat.servertools`）。编译期依赖 `net.luckperms:api`（`compileOnly`，不打进 jar），运行时由服务器上的 LuckPerms 提供；没装 LuckPerms 时会捕获 `NoClassDefFoundError`，日志提示一次后一律拒绝，不会崩服 |

`/aichat tools` 会直接把当前开关、前缀、权限方式、名单和「你自己能不能用」列出来，排查权限问题先看它。

### 安全边界

- **路径**：所有路径都相对服务器根目录解析，解析后必须仍在根目录内（含符号链接解析后的真实路径），越界直接拒绝。
- **保护名单**：`server_tools_deny_paths` 里的路径读写都拒绝，默认挡住 `config/aichat/config.json`、`system.json`、`server_admins.json`（里面有 API Key 和名单）以及回收目录本身。目录保护会连带其下所有内容。
- **大小**：读取 / 写入各有上限（默认 48 KB / 64 KB），列目录有条目上限。
- **删除**：默认进回收目录而不是直接抹掉，AI 想恢复用 `op=move` 移回即可；关闭回收站（`server_tools_trash_enabled: false`）后删除不可恢复，非空目录还需要显式 `recursive=true`。
- **二进制**：jar / 压缩包这类文件只报大小，不返回内容。
- **审计**：每次工具调用都会在服务端日志留一条 `[server-tools] 管理员 <玩家> 调用 op=... path=...`。
- **规则不入提示词文件**：前缀、权限、可用操作这些说明由模组在读取提示词时自动追加（和记忆功能一样），不写进你的 `system_prompt.txt`——升级不会覆盖你改过的提示词，也不会因为你改了提示词就把权限规则改掉。
- **广播可控**：`hide` / flag 模式下模组会取消原版广播，再手动投递给发送者、管理员和控制台，普通玩家看不到这条请求。
- **不进消息缓冲**：特权请求跳过防抖缓冲直接处理，避免同一时间别人的聊天被拼进这条授权请求里。

### 管理员名单文件

`config/aichat/server_admins.json`（`list` 模式生效）：

```json
{
  "note": "服务器管理工具的管理员名单；也可以用 /aichat tools add|remove <玩家> 维护。按玩家名匹配，忽略大小写。",
  "players": [
    "Senmo"
  ]
}
```

---
## 配置升级

`config.json` 与 `system.json` 都带版本号。模组启动或执行 `/aichat reload` 时，
若文件版本低于当前版本，会先备份为 `.v<版本>.bak`，再用默认模板补齐缺失字段，已有值不会被覆盖。

当前 `config.json` 为 **v6**、`system.json` 为 **v2**。从 1.8.0（v5）及更早版本升级时，会自动补上 `server_tools_*` 这一组字段（默认全部关闭），原文件备份为 `config.json.v5.bak` / `system.json.v1.bak`。

2.0.0 起配置文件**由 `ConfigDoc` 渲染**：字段顺序、默认值和中文注释都来自同一处定义，重建文件时不会丢注释，也不会出现「模板和代码对不上」。升级只在版本变化时重写一次；平时你手改的注释、缩进、字段顺序都会原样保留。

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
- 模型可通过 `read_knowledge_file` 读取 `knowledge/` 下的文件；工具描述里直接列出真实文件名，文件读取受白名单和路径校验限制。模型可连续多轮读文件（默认最多 4 轮，之后会发一次不带工具的请求收尾），单个文件超过 6000 字符会截断。`enable_tools` 为 `false` 时不发送知识库与搜索工具（记忆工具只看 `memory_enabled`）。
- 模型可通过 `web_search` 联网搜索（见「联网搜索」），一次对话最多一次；`search_enabled` 为 `false` 时不注册该工具。
- 模型可通过 `memory_read` / `memory_write` 读写 `memory/` 下的记忆文件（见「记忆功能」），跨对话保存约定和提醒；`memory_enabled` 为 `false` 时不注册这两个工具。
- 管理员用前缀（默认 `!+`）发起的请求，才会注册 `server_files` 工具（见「服务器管理工具」）；每次调用都写审计日志，删除默认进回收目录。
- `/aichat reload` 重新读取 `config.json` 与 `system.json`，失败时保留原配置并提示错误。
- 控制台编码兼容：中文 Windows 的控制台/日志编码是 GBK，AI 回复里的颜文字常含 GBK 表示不了的字符（`｡` `・` `∀` `ﾟ` `♪` `๑` 等）。这类字符写进控制台会让 MCDR 等包装器抛 `UnicodeEncodeError`，严重时 MCDR 主循环 CRITICAL 刷屏。模组会按 `sun.stdout.encoding` / `native.encoding` 判断编码，只对控制台那份做处理（控制台丢掉表示不了的字符，debug 日志转义成 `\uXXXX`），玩家聊天框仍是完整颜文字；UTF-8 控制台不受影响。若要根治，可给包装器进程设置 `PYTHONUTF8=1`（MCDR）让他自己的 stdout 也用 UTF-8。
- 不创建假玩家，不执行游戏操作。

## 测试

1. 配置 API Key，启动服务端，确认日志出现 `AI Chat v<版本> 已加载`（版本号能直接看出跑的是哪个 jar）。
2. 发送普通聊天，确认收到带前缀的回复。
3. 连续对话两句，确认 AI 能引用上一句（上下文记忆）。
4. 执行 `/say hi`，确认不触发 AI。
5. 玩家加入，确认收到欢迎消息。
6. 在 `knowledge/` 添加 `server_info.txt`，询问相关内容，确认模型读取文件并回答。
7. 开启双模型模式后再次对话，确认 judge 与 reply 的 token 都记录在日志中。
8. 把 `memory_enabled` 设为 `true` 并 `/aichat reload`，对 AI 说「记住：小明上线时提醒他交材料」；执行 `/aichat memory` 应看到新增的 `.md` 文件，服务端日志有「记忆写入：…」。重启服务端后再问「小明上线要提醒什么」，确认它能读回记忆。
9. 把 `memory_max_file_kb` 临时改成 1，让 AI 反复追加同一文件，确认超限时它收到压缩指引并改用 `overwrite`。
10. 把 `server_tools_enabled` 设为 `true` 并 `/aichat reload`，用 op 4 级账号发 `!+ 看一下 mods 目录`，确认 AI 列目录；再用普通玩家账号发同样的前缀消息，确认被拒（聊天框提示无权限，日志里没有 API 请求）。
11. 执行 `/aichat tools`，确认开关、前缀、权限方式、名单都显示正确；`server_tools_permission_mode` 改成 `list` 后，`/aichat tools add <非op玩家>`，让那名玩家发前缀消息验证可用。
12. 让 AI 删一个临时文件，确认它进了 `.aichat-trash/<时间戳>/`；让它读 `config/aichat/config.json`，确认被 `server_tools_deny_paths` 拒绝。

## 说明

- 上下文条数由 `max_context_messages` 控制，调大能记住更多内容，但每次请求的 prompt token 也会增加。
- API Key 以明文保存在 `config/aichat/config.json` 与 `system.json`，请限制配置文件访问权限。

### 排查「AI 不回复」

1. 看启动日志的版本号，确认服务端跑的是哪个 jar。
2. `/aichat debug` 打开调试，再发一句聊天，看日志里的 `[debug] 解析结果：reply=...`。
3. 如果模型在「分析日志 / 总结事件」而不是输出 `{"action":...}`：说明提示词没到模型，多半是中转站丢了 `system`，确认 `system_prompt_in_user` 为 `true`。
4. 如果响应里 `content` 为空且 `finish_reason=length`：输出预算被思考 token 吃光了，调大 `max_output_tokens`（或换非推理模型）。
5. 如果看到「工具调用达到 N 轮上限」：模型在读知识库时反复猜文件名，检查 `knowledge/` 目录里是否真有它要的文件。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。你可以自由使用、修改、分发，甚至用于商业项目，只需保留版权声明。

