# AI Chat

让一个可配置的 AI 住进 Minecraft 服务器聊天框：它会读知识库、查记忆、联网搜索，被授权后还能翻服务器日志和模组列表。

支持 OpenAI 兼容与 Anthropic Messages 两类 API，兼容 DeepSeek / 千问 / Kimi / 智谱 / 豆包 / Gemini / OpenRouter / Ollama 等平台或中转站。

| 模组版本 | 配置文件版本 | 环境 |
|---|---|---|
| 2.0.1 | `config.json` v7 · `system.json` v2 | MC 26.1.2 · Fabric Loader 0.19.4+ · Fabric API 0.155.3+26.1.2 · Java 25 |

---

## 目录

- [1. 快速开始](#1-快速开始)
- [2. 能力总览](#2-能力总览)
- [3. 运行行为](#3-运行行为)
- [4. 工具](#4-工具)
- [5. 消息缓冲](#5-消息缓冲)
- [6. 双模型模式](#6-双模型模式)
- [7. 命令](#7-命令)
- [8. 配置参考](#8-配置参考)
- [9. 模型兼容性](#9-模型兼容性)
- [10. 配置升级](#10-配置升级)
- [11. 排查手册](#11-排查手册)
- [12. 自测清单](#12-自测清单)
- [13. 文档与许可证](#13-文档与许可证)

---

## 1. 快速开始

### 环境要求

| 项目 | 要求 |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.4+ |
| Fabric API | 0.155.3+26.1.2 |
| Java | 25 |
| LuckPerms | 可选。只有 `server_tools_permission_mode: luckperms` 和 `/aichat tools` 的权限判定用得上，没装不影响其他功能 |

### 安装

1. 把模组 jar 和匹配 26.1.2 的 Fabric API 放进服务端 `mods/`。
2. 启动服务端。首次启动在 `config/aichat/` 生成：

   ```
   config.json          主配置（自带中文注释）
   system.json          双模型模式配置
   system_prompt.txt    系统提示词（格式契约 + 工具规则）
   persona_prompt.txt   人设
   judge_prompt.txt     双模型：判断提示词
   reply_prompt.txt     双模型：回复提示词
   knowledge/           知识库目录
   ```

3. 在 `config.json` 填 `api_key`（双模型模式填 `system.json`），`/aichat reload` 或重启生效。改配置不需要重新构建。

> **配置文件自带注释**：每个字段上方都有 `//` 说明，不用翻文档。以 `//` 开头的行读取时会被忽略，可以放心改、也可以自己加注释；模组平时不重写你的文件，只有 `config_version` 升级时才备份（`.v<旧版本>.bak`）并重写一次。

### 从源码构建

```bash
export JAVA_HOME=/path/to/jdk-25    # Windows: set JAVA_HOME=D:\path\to\jdk-25
./gradlew build                     # Windows: gradlew.bat build
```

产物在 `build/libs/aichat-<version>.jar`。项目用 Gradle Wrapper 9.7.1（Loom 1.18.4 要求 Gradle 9.7+）；`net.luckperms:api` 是 `compileOnly` 依赖，首次构建需联网解析。

---

## 2. 能力总览

| 能力 | 默认 | 关键配置 | 详见 |
|---|---|---|---|
| 聊天回复（JSON 契约 + 上下文历史） | 开 | `max_context_messages` | [§3](#3-运行行为) |
| 加入欢迎 / 退出静默 | 开 | `enable_join_quit_events` | [§3](#3-运行行为) |
| 消息防抖缓冲（合并成一次请求） | 开 | `buffer_*` | [§5](#5-消息缓冲) |
| 知识库文件读取 `read_knowledge_file` | 关 | `enable_tools`、`knowledge_dir` | [§4.1](#41-知识库-read_knowledge_file) |
| 联网搜索 `web_search` | 开 | `search_*` | [§4.2](#42-联网搜索-web_search) |
| 跨对话记忆 `memory_read` / `memory_write` | 开 | `memory_*` | [§4.3](#43-记忆-memory_read--memory_write) |
| 服务器文件管理 `server_files` | **关** | `server_tools_*` | [§4.4](#44-服务器管理-server_files默认关闭) |
| 双模型模式（先判断再回复） | 关 | `system.json` | [§6](#6-双模型模式) |
| token 用量统计 | 开 | — | [§7](#7-命令) |

---

## 3. 运行行为

**会触发 AI 的**：玩家聊天；玩家加入（发送欢迎消息）。**不会触发的**：`/say`、`/me` 等命令消息、玩家退出（默认忽略）、被 ban 的玩家、全局关闭时的所有消息。

- **回复解析**：模型返回 `{"action":"reply","content":"..."}` 或只返回 `{"content":"..."}` 时广播，返回 `{"action":"ignore"}` 时静默。返回值带 ```` ```json ```` 代码块或前后有少量文字也能正确解析。
- **上下文**：最近 `max_context_messages` 条记录（玩家事件与 AI 回复都计入）作为历史随请求发送。
- **玩家过滤**：`off` 的玩家发言不发 AI、也收不到 AI 消息；`onlyread` 只收不发；被 ban 的玩家彻底不透传。拦截在请求发出前完成，不浪费 token。
- **AI 广播逐个投递**：`off` 玩家收不到，`onlyread` 与 `on` 玩家正常接收。
- **不创建假玩家，不执行游戏操作**；AI 只能通过工具读写文件。
- **停服止损（2.0.1）**：收到 `stop` 后立即停止发送一切信息、清空缓冲、取消在途 HTTP 请求（真正掐掉生成中的推理）。停服会断开所有在线玩家，旧版本会把每条 quit 都提交给 AI，一次停服白烧十几条请求——现在一条都不会发。
- **控制台编码**：中文 Windows 控制台/日志是 GBK，AI 回复里的颜文字常含 GBK 表示不了的字符（`｡` `・` `∀` `ﾟ` `♪` `๑` 等），会让 MCDR 等包装器抛 `UnicodeEncodeError`。模组按 `sun.stdout.encoding` / `native.encoding` 判断编码，只对控制台那份丢字符（debug 日志转义成 `\uXXXX`），玩家聊天框仍是完整颜文字。根治办法是给包装器设 `PYTHONUTF8=1`。

---

## 4. 工具

模组最多注册四个工具；带工具时**不发送** `response_format=json_object`（两家协议的工具调用与强制 JSON 互斥）。

统一约束：

- **轮次**：一次对话最多 4 轮工具调用。第 5 轮仍保留工具声明但追加「本轮不要再调用工具，直接给结论」；第 6 轮还不听话才回收工具声明。这样模型不会因为工具突然消失而把调用写成正文里的私有标记（如 `<｜｜DSML｜｜>`）导致玩家收不到任何回复。
- **总预算（2.0.1）**：`tool_result_budget_kb`（默认 24 KB，`0` = 不限）限制**整次对话**回灌给模型的工具结果总量。每轮调用都会把历史结果重新发一遍，读一次大日志能被计费四五次；用尽后不再执行新工具，直接让模型基于已有信息作答。
- **兜底（2.0.1）**：用过工具但最终没拿到合法 JSON 时，播报 `fallback_reply`（默认「（这轮没理出结果，请再说一次）」，留空 = 保持静默），不再静默失联。
- **网络重试（2.0.1）**：连接超时 / SSL 握手被掐断 / 429 / 5xx 自动重试 1 次（间隔 2 秒）；4xx 参数错误不重试，直接报错。
- **规则自动注入**：工具规则由模组读取提示词时追加，不写进你的 `system_prompt.txt`，升级不会覆盖你的提示词。

### 4.1 知识库 `read_knowledge_file`

`enable_tools: true` 时注册。工具描述里直接列出 `knowledge/` 下的真实文件名（放在这里的就是想让 AI 看见的资料：服务器规则、常见问题、玩法说明），模型可连续多轮读取。

- 单个文件超过 6000 字符会截断。
- 读取受文件名白名单与路径校验限制，不能跳出 `knowledge/`。
- 关闭方式：`enable_tools: false`。

### 4.2 联网搜索 `web_search`

模型判断需要实时信息（新闻、天气、比分、价格等）时调用，模组去搜索接口取结果，再把「标题 + 摘要 + 链接」回灌给模型组织回答。**搜索由模组执行，与模型平台无关**——中转站不支持服务端搜索也能用。

| provider | 端点 | Key | 说明 |
|---|---|---|---|
| `bing`（默认） | `https://cn.bing.com/search` | 不需要 | 抓结果页 HTML，国内直连；最省事，但属页面抓取，Bing 改版或要求验证时可能失效 |
| `searxng` | 自建实例 `/search?format=json` | 不需要 | 需自己有 SearXNG，`search_api_url` 必填，零费用不限量 |
| `tavily` | `https://api.tavily.com/search` | 需要 | 有免费额度，境外节点，响应偏慢 |
| `bocha` | `https://api.bochaai.com/v1/web-search` | 需要 | 中文结果质量好，按次计费 |
| `zhipu` | `https://open.bigmodel.cn/api/paas/v4/web_search` | 需要 | 国内直连，单价低 |

- 一次对话最多搜索一次，结果总长上限 4000 字符（单条摘要 200 字符），超出截断。
- 搜索失败不重试，把 `搜索失败: <原因>` 当工具结果回灌并写 WARN 日志，让模型照实说「没搜到」而不是编造。
- 搜索在 `aichat-worker` 线程执行，不阻塞服务端主线程。
- `search_enabled: false` 关闭；provider 需要 Key 却没填时工具不注册（模型看不到搜索能力，不会反复尝试失败）。

### 4.3 记忆 `memory_read` / `memory_write`

上下文窗口只装得下最近几条消息，「小明上线时提醒他交材料」这类约定一多就丢。记忆功能给 AI 一个自己维护的记事本：写进 `memory/` 目录，下次对话再读回来。

| 工具 | 参数 | 作用 |
|---|---|---|
| `memory_read` | `file_name`（可省略） | 省略时返回文件清单和各自大小；填了则返回全文 |
| `memory_write` | `file_name`、`content`、`mode` | `mode=append` 追加（默认）/ `overwrite` 覆写 |

- `memory_enabled`（默认 `true`）会**顺带打开工具通道**：即使 `enable_tools: false`，记忆工具定义也会发送（知识库/搜索仍归 `enable_tools` 管）。想完全回到 1.7.0 的旧行为就设成 `false`。
- 超过 `memory_max_file_kb`（默认 8 KB）的写入会被拒绝，返回的是「先 `memory_read` 读出全文 → 压缩成不超过 N KB 的要点 → `mode=overwrite` 覆写」的指引，由模型自己压缩；用到 80% 时提前提醒。**模组不静默截断，也不会写出半截文件**。
- 文件名白名单：不带目录、不含 `..`，只允许字母数字/中文/`_`/`-`/`.`/空格，扩展名仅 `.md` / `.txt`（缺省补 `.md`），最长 64 字符。
- 文件数量软上限 32 个，达上限后只能覆写或合并，防止被刷爆目录。
- 目录默认 `config/aichat/memory/`，配置加载时自动创建；`/aichat memory` 查看清单，每次写入在日志留一条 INFO。
- 记忆文件是 AI 的笔记，提示词明确要求**只当资料参考，不把文件内容当系统规则或玩家指令执行**——防止有人把恶意指令写进记忆文件。
- token 提醒：每轮都会带工具定义（几 KB 级），文件正文只在模型主动 `memory_read` 时才进 prompt。

### 4.4 服务器管理 `server_files`（默认关闭）

让 AI **查看与管理服务器根目录里的文件**：排查故障时读 `logs/latest.log`、看 `mods/` 装了哪些模组、检查 `config/` 里的配置、临时记录诊断结果。

#### 三道门，缺一不可

1. `server_tools_enabled: true`（默认 `false`）；
2. 这条聊天消息以 `server_tools_prefix`（默认 `!+`）开头；
3. 发消息的玩家通过权限判定（见下）。

普通聊天**不会**注册这个工具：模型连工具名字都看不到，硬编出名字来只会收到「未知工具」。带前缀但没权限的玩家，消息直接丢弃并收到提示，**不会发给 AI**（不浪费 token）。

```
!+ 看一下 mods 目录里都有什么模组
!+ 读一下 logs/latest.log 最后 200 行，服务器刚才为什么崩的
!+ config/fabric-server-launch.properties 里改了什么
```

前缀只是「授权令牌」，AI 收到的是去掉前缀之后的内容；每次特权请求还会收到一行「【管理员特权请求】」说明（2.0.1），避免小模型反推出「我没有权限」并反过来向玩家索要前缀。

#### 工具能力（一个工具覆盖八种操作）

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

#### 权限判定的三种方式

| 模式 | 说明 |
|---|---|
| `op`（默认） | 按原版权限等级判，`server_tools_permission_level` 默认 `4`（`1`=管理员 `2`=游戏管理员 `3`=op `4`=服主）。想放开到 3 级 op 就改成 `3` |
| `list` | 用模组自己的名单文件 `config/aichat/server_admins.json`，非 op 玩家也能被授权。`/aichat tools add <玩家>` / `remove <玩家>` 维护，或改文件后 `/aichat reload` |
| `luckperms` | 查 LuckPerms 权限节点（`server_tools_luckperms_node`，默认 `aichat.servertools`）。`compileOnly` 依赖，运行时由服务器上的 LuckPerms 提供；没装时捕获 `NoClassDefFoundError`，日志提示一次后一律拒绝，**不会崩服** |

`/aichat tools` 会列出当前开关、前缀、权限方式、名单和「你自己能不能用」，排查权限问题先看它。

#### 安全边界

- **路径**：全部相对服务器根目录解析，解析后（含符号链接真实路径）必须仍在根目录内，越界直接拒绝。
- **保护名单**：`server_tools_deny_paths` 默认挡住 `config/aichat/config.json`、`system.json`、`server_admins.json`（含 API Key 和名单）和回收目录本身；目录保护连带其下所有内容。
- **大小**：读 `server_tools_read_max_kb`（默认 48 KB）/ 写 `server_tools_write_max_kb`（默认 64 KB）；列目录上限 `server_tools_list_max_entries`（默认 200）。
- **删除**：默认进回收目录而非抹掉，AI 可用 `op=move` 移回；关闭 `server_tools_trash_enabled` 后删除不可恢复，非空目录还需显式 `recursive=true`。
- **二进制**：jar / 压缩包只报大小，不返回内容。
- **审计**：每次调用写一条 `[server-tools] 管理员 <玩家> 调用 op=... path=...`。
- **不进消息缓冲**：特权请求跳过防抖缓冲立即处理，避免别人同一时间的聊天被拼进这条授权请求。
- **广播可控**：`server_tools_broadcast` 决定其他玩家能否看到这条请求。`show`（默认）原样广播；`hide` 完全不广播，只发给发送者、管理员和控制台；`show_by_flag` 默认不广播、消息末尾加 `-f` 才广播；`hide_by_flag` 默认广播、加 `-f` 就不广播。标志位会被剥掉，不进 AI 输入。

#### 管理员名单文件

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

## 5. 消息缓冲

默认开启，用于防抖合并、省 token。`buffer_delay_seconds`（默认 5）是**最后一条消息之后**再等几秒——等待期间来了新事件（聊天/加入/退出）会重置计时并合并进同一批，所以一条请求可以带上好几句。

以下情况**立即发送、不等待**：

| 情况 | 日志 | 原因 |
|---|---|---|
| `!+` 特权请求 | `服务器管理工具请求：xxx（特权请求跳过缓冲，立即发送）` | 缓冲会把别人的话拼进来，管理员授权就说不清来源 |
| 缓冲达上限 | `缓冲达到上限（10 条 / 500 字符），立即发送 N 条消息` | `buffer_max_messages` / `buffer_max_chars` |
| `buffer_enabled: false` | `[debug] 消息缓冲已关闭（buffer_enabled=false），立即发送` | 配置关闭 |

被 ban、`off`、`onlyread` 的玩家事件在进入缓冲前就被拦截。

排查时可看的日志：

```
[debug] 消息进入缓冲：已攒 2 条 / 46 字符，5 秒后发送     ← /aichat debug 开启后显示
缓冲到期：合并 2 条消息后发送（等待 5 秒）                ← 合并了多条才打
```

**容易误判**：玩家 join 会进缓冲（`enable_join_quit_events: true`），5 秒后才发；如果他在 5 秒内又说了句话，计时重置，于是「刚说完就收到回复」——那条回复其实是「join + 那句话」合并后的结果。

---

## 6. 双模型模式

先用便宜快速的模型判断「要不要回复」，需要回复时再调另一个模型生成内容。两套 API 完全独立，可以指向不同平台、不同 Key 和模型。判断模型与回复模型都会收到最近 `max_context_messages` 条历史，`judge_prompt.txt` 里依赖上下文的规则（如「玩家在和其他人聊天时不要插话」）才能生效。

### 开启步骤

1. 打开 `config/aichat/system.json`。
2. 把 `"dual_model_enabled": false` 改成 `true`。
3. 填 `judge`（判断）和 `reply`（生成）两套配置。
4. `/aichat reload` 或重启。
5. 用 `/aichat token` 观察用量；日志会分别记录两个模型的请求。

```json
{
  "version": 2,
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

- `judge` 只返回 `{"action":"reply"}` 或 `{"action":"ignore"}`，不发送工具定义，适合小模型。
- `reply` 只在需要回复时调用；是否发送知识库/搜索工具由 `config.json` 的 `enable_tools` 控制，记忆工具看 `memory_enabled`。
- `api_url` / `api_key` / `model` / `api_type` 留空时回退到 `config.json` 的同名配置，可以只填一套先跑通。
- 判断模型默认输出预算 1024 token；用默认开启思考的推理模型（如 GLM-4.7 系列）时，模组会自动发 `"thinking": {"type": "disabled"}`，并在遇到 `finish_reason=length` + 空 `content` 时放大预算重试一次。GLM-5.3 系列强制思考，只能调大 `max_output_tokens`。
- 关掉只需把 `dual_model_enabled` 改回 `false` 再 `/aichat reload`。

---

## 7. 命令

所有玩家：

| 命令 | 说明 |
|---|---|
| `/aichat on` | 默认状态：发言发给 AI，也能看到 AI 的消息 |
| `/aichat off` | 发言不发给 AI，也看不到 AI 的消息 |
| `/aichat onlyread` | 发言不发给 AI，但能看到 AI 的消息 |

需要管理员权限（权限等级 2，即 OP）：

| 命令 | 说明 |
|---|---|
| `/aichat token` | 最近 7 天 token 用量（按天汇总） |
| `/aichat token clear` | 清空统计并重新计算（原文件按时间戳备份） |
| `/aichat reload` | 热重载 `config.json` 与 `system.json` |
| `/aichat enable` / `/aichat disable` | 全局开启 / 关闭 AI 聊天 |
| `/aichat debug` | 切换调试模式：聊天框显示 token 用量（仅管理员），并把接口原始响应与解析结果写进日志 |
| `/aichat memory` | 查看记忆文件清单 |
| `/aichat ban <玩家>` / `unban <玩家>` / `banlist` | 禁止 / 解除 / 查看禁止名单（离线玩家也可操作） |
| `/aichat tools` | 服务器管理工具状态：开关、前缀、权限方式、广播方式、名单、你自己能不能用 |
| `/aichat tools add <玩家>` / `remove <玩家>` | 维护管理员名单（需 op 4 级；仅 `list` 模式生效） |

运行期状态：`config/aichat/state.json`（全局开关、调试模式、禁止列表、玩家模式）、`config/aichat/usage.jsonl`（按行追加的 token 用量），都可直接查看。

---

## 8. 配置参考

> 全部 48 个字段都在 `config.json` 里，并且**文件内自带同样的中文注释**。下表按用途分组，默认值与代码一致。

### 8.1 连接与模型

| 字段 | 默认 | 说明 |
|---|---|---|
| `api_url` | `https://api.deepseek.com/chat/completions` | 接口地址；也可只填 base URL（如 `https://xxx/v1`），模组自动补 `/chat/completions` |
| `api_key` | 空 | API Key；保留占位值时跳过请求（本地 Ollama / LM Studio 可留空） |
| `model` | `deepseek-flash` | 模型名称 |
| `api_type` | `auto` | `auto` / `openai` / `deepseek` / `anthropic`；`auto` 按 `api_url` 判断 |
| `reasoning_effort` | 空 | 推理强度（`low` / `medium` / `high`），留空不发送 |
| `request_timeout_seconds` | `20` | 单次 HTTP 请求整体超时 |

### 8.2 对话与输出

| 字段 | 默认 | 说明 |
|---|---|---|
| `system_prompt_file` | `system_prompt.txt` | 系统提示词文件名，相对 `config/aichat/` |
| `persona_prompt_file` | `persona_prompt.txt` | 人设提示词文件名，相对 `config/aichat/` |
| `reply_prefix` | `[AI] ` | AI 消息前缀；空字符串 = 不加前缀 |
| `max_context_messages` | `10` | 随请求发送的最近记录条数（玩家事件与 AI 回复都计入） |
| `enable_join_quit_events` | `true` | 是否对加入/退出事件触发 AI（默认加入发欢迎、退出静默，可在提示词里改） |
| `use_json_mode` | `true` | 是否发送 `response_format=json_object`；带工具时不生效 |
| `enable_thinking` | `false` | 是否开启深度思考 |
| `send_thinking_field` | `false` | 是否发送 `thinking` 字段；开启后部分平台（如千问 MaaS）不返回 `tool_calls` |
| `max_output_tokens` | `0` | 最大输出 token，`0` = 不发送（Anthropic 默认 4096） |
| `thinking_budget_tokens` | `1024` | Anthropic 思考预算 |
| `system_prompt_in_user` | `true` | 把系统提示词（格式契约 + 工具规则 + 文件列表 + 人设）再拼进最后一条用户消息。中转站常悄悄丢掉 `system` 角色，表现为模型不输出 JSON、瞎猜文件名；接口确实支持 `system` 时设为 `false` 可省一份 token |

### 8.3 知识库与工具

| 字段 | 默认 | 说明 |
|---|---|---|
| `knowledge_dir` | `knowledge` | 知识库目录名，相对 `config/aichat/` |
| `enable_tools` | `false` | 是否发送知识库与搜索工具定义；关闭时 `use_json_mode` 生效 |
| `tool_result_budget_kb` | `24` | 单次对话工具结果总预算（KB），`0` = 不限；用尽后不再执行新工具 |
| `fallback_reply` | `（这轮没理出结果，请再说一次）` | 工具用过但没拿到合法 JSON 时代替沉默的一句话；留空 = 静默 |

### 8.4 联网搜索

| 字段 | 默认 | 说明 |
|---|---|---|
| `search_enabled` | `true` | 是否注册 `web_search`；关闭后行为与 1.6.2 一致 |
| `search_provider` | `bing` | `bing` / `searxng` / `tavily` / `bocha` / `zhipu` |
| `search_api_url` | 空 | 留空用 provider 默认端点；`searxng` 必填 |
| `search_api_key` | 空 | 需要 Key 的 provider 填写；`bing` / `searxng` 留空 |
| `search_max_results` | `5` | 一次最多取几条结果（上限 10） |
| `search_timeout_seconds` | `10` | 搜索请求超时，与对话超时独立 |

### 8.5 记忆

| 字段 | 默认 | 说明 |
|---|---|---|
| `memory_enabled` | `true` | 是否开启记忆；开启后顺带打开工具通道 |
| `memory_dir` | `memory` | 记忆文件夹名，相对 `config/aichat/` |
| `memory_max_file_kb` | `8` | 单个记忆文件上限（KB） |

### 8.6 消息缓冲

| 字段 | 默认 | 说明 |
|---|---|---|
| `buffer_enabled` | `true` | 是否启用防抖缓冲 |
| `buffer_delay_seconds` | `5` | 最后一条消息后等待秒数 |
| `buffer_max_messages` | `10` | 缓冲区最多条数，满了立即发送 |
| `buffer_max_chars` | `500` | 缓冲区最多字符数，满了立即发送 |

### 8.7 服务器管理工具

| 字段 | 默认 | 说明 |
|---|---|---|
| `server_tools_enabled` | `false` | 总开关 |
| `server_tools_prefix` | `!+` | 特权请求前缀；留空 = 关闭前缀通道 |
| `server_tools_permission_mode` | `op` | `op` / `list` / `luckperms` |
| `server_tools_permission_level` | `4` | `op` 模式要求的权限等级 |
| `server_tools_luckperms_node` | `aichat.servertools` | `luckperms` 模式查询的节点 |
| `server_tools_admins_file` | `server_admins.json` | `list` 模式的名单文件 |
| `server_tools_read_max_kb` | `48` | 单次读取上限，超出截断并提示分段读 |
| `server_tools_write_max_kb` | `64` | 单次写入上限 |
| `server_tools_list_max_entries` | `200` | 列目录最多返回项数 |
| `server_tools_trash_enabled` | `true` | 删除移入 `.aichat-trash/<时间戳>/`（强烈建议保持） |
| `server_tools_broadcast` | `show` | `show` / `hide` / `show_by_flag` / `hide_by_flag` |
| `server_tools_broadcast_flag` | `-f` | 两种 flag 模式的尾缀，不会发给 AI |
| `server_tools_deny_paths` | 见下 | 禁止读写的路径（相对服务器根目录） |

`server_tools_deny_paths` 默认值：

```json
[
  "config/aichat/config.json",
  "config/aichat/system.json",
  "config/aichat/server_admins.json",
  ".aichat-trash"
]
```

### 8.8 版本

| 字段 | 默认 | 说明 |
|---|---|---|
| `config_version` | `7` | 配置版本，模组用它判断是否需要补字段。**不要手动改小**，否则会被当成旧文件重写 |

---

## 9. 模型兼容性

`api_type: auto` 时按 `api_url` 自动判断协议：

| 协议 | 端点 | 认证 |
|---|---|---|
| OpenAI 兼容 | `.../chat/completions` | `Authorization: Bearer` |
| Anthropic Messages | `.../v1/messages` | `x-api-key` |

常见平台的 `api_url`：

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

注意事项：

- 默认不发送 `thinking` 字段。部分平台（如千问 MaaS）收到关闭思考的参数后不返回 `tool_calls`，需要工具调用时保持 `send_thinking_field: false`。
- 判断模型在智谱 GLM 平台（`bigmodel.cn` / `z.ai` / `glm-*`）上自动关闭思考，避免推理 token 吃满输出预算；`glm-5.3` 系列强制思考，不走该逻辑。
- 推理模型的思考 token 同样计入 `max_tokens`，预算给小了会得到 `finish_reason=length` + 空 `content`；模组遇到会自动放大预算重试一次。
- OpenAI Responses API、Gemini 原生 `generateContent` 不在支持范围，请用各自的 Chat Completions 兼容端点。

---

## 10. 配置升级

`config.json` 与 `system.json` 都带版本号。启动或 `/aichat reload` 时，若文件版本低于当前版本，会先备份为 `.v<版本>.bak`，再补齐缺失字段与新注释，**已有值不会被覆盖**。

当前版本：`config.json` **v7**、`system.json` **v2**。升级路径示例：从 1.8.0（v5）升上来会备份成 `config.json.v5.bak` / `system.json.v1.bak`，并补上 `server_tools_*` 一组字段（默认全部关闭）；从 2.0.0（v6）升上来会备份成 `config.json.v6.bak`，补上 `tool_result_budget_kb` 与 `fallback_reply`。

2.0.0 起配置文件由 `ConfigDoc` 渲染：字段顺序、默认值、中文注释来自同一处定义，重建文件不会丢注释，也不会「模板和代码对不上」。只在版本变化时重写一次；平时你手改的注释、缩进、字段顺序都原样保留。

**提示词文件不会被覆盖**：`system_prompt.txt` / `persona_prompt.txt` / `judge_prompt.txt` / `reply_prompt.txt` 只在不存在时创建。升级后需要手动同步提示词的改动，具体改法见 [UPDATE-2.0.1.md](UPDATE-2.0.1.md) 第五节。

---

## 11. 排查手册

### AI 完全不回复

1. 看启动日志的版本号（`AI Chat v2.0.1 已加载`），确认跑的是哪个 jar。
2. `/aichat debug` 打开调试，再发一句，看 `[debug] 解析结果：reply=...`。
3. 原始响应有内容但 `reply=false` → 问题在解析或提示词；原始响应本身没内容 → 问题在模型或平台。
4. 模型在「分析日志 / 总结事件」而不输出 `{"action":...}` → 提示词没到模型，多半是中转站丢了 `system`，确认 `system_prompt_in_user: true`。
5. `content` 为空且 `finish_reason=length` → 思考 token 吃光输出预算，调大 `max_output_tokens` 或换非推理模型。
6. 日志出现「工具调用达到 N 轮上限」→ 模型在反复猜文件名，检查 `knowledge/` 里是否真有它要的文件。
7. 出现过工具调用但最终没回复 → 看有没有 `fallback_reply` 兜底话术与 WARN「特权请求没有调用任何工具」。

### 特权请求说「我没有权限」或反要 `!+` 前缀

- 确认 jar 是 2.0.1+，并且 **手动更新了 `system_prompt.txt` 的「工具」第 3 条**（模组不覆盖已有提示词）。
- `/aichat tools` 确认开关、前缀、权限方式与「你自己能不能用」。
- 日志里 `服务器管理工具请求：xxx（特权请求跳过缓冲，立即发送）` 说明前缀被识别；若同时出现 `特权请求没有调用任何工具` 的 WARN，说明模型没注意到授权标注。

### 回复比预期快 / 好像没走缓冲

- `buffer_delay_seconds` 是**最后一条消息之后**的等待时间，新消息会重置。
- `!+` 特权请求按设计跳过缓冲；撞 `buffer_max_messages` / `buffer_max_chars` 也会立即发。见 [§5](#5-消息缓冲) 的三行日志。

### token 用量异常高

- 打开 `/aichat debug`，看 `[debug] 工具调用 name=... 结果长度=... 预算剩余=...`：预算剩余掉得快说明单次工具结果太大，调小 `server_tools_read_max_kb` 或 `tool_result_budget_kb`。
- 调小 `max_context_messages`、启用省 token 的缓冲、避免一次对话反复读大文件。
- `/aichat token` 看按天汇总，确认是哪个模型在烧。

### 停服时日志刷一堆请求

2.0.1 起不会了：停服瞬间只留一行

```
服务器正在关闭：已停止 AI 请求，清空缓冲区（丢弃 N 条待处理事件），中止 N 个在途请求
```

如果还看到成批请求，确认服务端跑的确实是 2.0.1+。

---

## 12. 自测清单

1. 填好 Key 启动，日志出现 `AI Chat v<版本> 已加载`。
2. 普通聊天收到带前缀的回复；连说两句能引用上一句（上下文）。
3. `/say hi` 不触发 AI；玩家加入收到欢迎消息。
4. 在 `knowledge/` 放 `server_info.txt`，问相关内容，模型读取后回答（`enable_tools: true`）。
5. 开双模型模式再聊，日志里 judge 与 reply 的 token 都有记录。
6. 说「记住：小明上线时提醒他交材料」→ `/aichat memory` 看到新文件、日志有写入记录；重启后再问能否读回。
7. 把 `memory_max_file_kb` 改成 1，让 AI 反复追加同一文件，确认超限时它收到压缩指引并改用 `overwrite`。
8. `server_tools_enabled: true` 后，op 4 级账号发 `!+ 看一下 mods 目录` 应列目录；普通玩家发同样消息被拒（聊天框提示，日志无 API 请求）。
9. `/aichat tools` 显示正确；切成 `list` 模式后 `tools add <非op玩家>`，该玩家发前缀消息可用。
10. 让 AI 删一个临时文件 → 进 `.aichat-trash/<时间戳>/`；让它读 `config/aichat/config.json` → 被 `deny_paths` 拒绝。
11. `!+ 读一下 logs/latest.log` → 看 `[debug] 工具调用 ... 预算剩余=...`，prompt token 不上万。
12. 有玩家在线时 `stop` → 只出现一行「服务器正在关闭…」，不再成批产生 token 用量。

---

## 13. 文档与许可证

| 文件 | 内容 |
|---|---|
| `README.md` | 本文件：完整用法、配置项、命令、排查 |
| `UPDATE-2.0.1.md` | 2.0.1 更新说明：五个修复的来龙去脉、提示词替换、验证清单 |
| `UPDATE-2.0.0.md` | 2.0.0 更新说明：服务器管理工具怎么用、升级步骤、已知限制 |
| `RELEASE-NOTES-2.0.1.md` | Release 正文：2.0.0 功能 + 2.0.1 修复的整合说明 |
| `CHANGELOG.md` | 逐版本变更记录 |

**安全提醒**：API Key 以明文保存在 `config/aichat/config.json` 与 `system.json`，请限制配置文件访问权限；`server_tools_deny_paths` 默认已挡住这两个文件，但操作系统层面仍建议收紧。

本项目采用 [MIT License](LICENSE) 开源。你可以自由使用、修改、分发，甚至用于商业项目，只需保留版权声明。
