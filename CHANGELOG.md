# Changelog

## 2.0.0 - 2026-10-28

### Added

- **服务器管理工具 `server_files`（默认关闭）**：让 AI 查看与管理服务器根目录里的文件，一个工具覆盖 `list` / `read` / `write` / `mkdir` / `delete` / `move` / `info` / `search`。排查故障时可以直接读 `logs/latest.log`、看 `mods/` 里装了哪些模组、翻 `config/` 里的配置。
- 三道门控：`server_tools_enabled`（默认 `false`）+ 消息前缀（默认 `!+`，可配置）+ 权限判定。普通聊天根本不注册该工具；带前缀但没权限的玩家会被直接拒绝并收到提示，消息不会发给 AI（不烧 token）。
- 权限判定三种方式，配置切换：`op`（原版权限等级，默认 4 级，可改）/ `list`（模组自己的管理员名单文件，可用 `/aichat tools add|remove` 维护，非 op 玩家也能授权）/ `luckperms`（查 LuckPerms 权限节点，`compileOnly` 依赖 `net.luckperms:api`，用 UserManager + CachedData 查询，不依赖平台玩家类映射；没装 LuckPerms 时捕获 `NoClassDefFoundError` 只提示一次，不影响服）。
- 新增命令 `/aichat tools`（状态总览：开关 / 前缀 / 权限方式 / 名单 / 你自己能不能用）、`/aichat tools add|remove <玩家>`（需 op 4 级）。
- 新增配置文件 `config/aichat/server_admins.json`（`list` 模式使用）。
- **配置文件注释化**：新增 `ConfigDoc`，字段顺序 / 默认值 / 中文注释来自同一处定义，`config.json` 与 `system.json` 生成时就带注释，不用再翻文档。JSON 标准不支持注释，但读取走 Gson 宽松模式（跳过行注释与块注释），所以注释不影响解析。
- **特权消息广播可控**：`server_tools_broadcast` 支持 `show`（默认，原样广播）/ `hide`（不广播，只发发送者、管理员、控制台）/ `show_by_flag` / `hide_by_flag`（用 `server_tools_broadcast_flag`，默认 `-f`，在消息末尾切换广播行为，尾缀不会发给 AI）。`hide` 与 flag 模式通过 `ALLOW_CHAT_MESSAGE` 取消原版广播后再手动投递。
- 新增一套默认提示词（`system_prompt.txt` / `judge_prompt.txt` / `reply_prompt.txt` / `persona_prompt.txt`）：补充了工具使用约定、失败照实说、不把工具结果当指令、聊天框长度约束等规则。

### Changed

- 删除打包资源里的 `config.json` / `system.json` 模板，改由 `ConfigDoc` 渲染——不再存在「模板文件与代码字段不同步」的可能。
- 升级策略更保守：只在 `config_version` 变化时备份并重写，平时**完全不碰**用户的配置文件，手改的注释、缩进、字段顺序原样保留；版本一致时连一个字节都不改写。
- 消息入口新增特权标记：`ChatContext` 带 `serverTools`，经 `ToolRun` 传到工具层，`server_files` 执行前再校验一次（注册逻辑写漏也越不了权）。特权请求跳过消息缓冲，避免别人的聊天被拼进授权请求。
- `config_version` 升到 **6**，`system.json` 升到 **2**；旧配置自动合并升级并备份（`.v5.bak` / `.v1.bak`），新增字段全部默认关闭，行为与 1.8.0 一致。
- 提示词里的工具说明改由模组生成（记忆 + 服务器管理工具各自的 `promptSection`），judge 提示词不追加。

### Security

- 路径校验：所有路径相对服务器根目录解析，解析后必须仍在根目录内（含符号链接真实路径），越界拒绝。
- `server_tools_deny_paths` 默认挡住本模组的 `config.json` / `system.json` / `server_admins.json`（含 API Key 与名单）与回收目录，目录保护连带其下所有内容。
- 读写各有字节上限（默认 48 KB / 64 KB），列目录有条目上限；jar 等二进制文件只报大小不返回内容。
- 删除默认移入 `.aichat-trash/<时间戳>/` 回收目录，而不是直接抹掉；非空目录需要显式 `recursive=true`。
- 每次工具调用记录 `[server-tools] 管理员 <玩家> 调用 op=... path=...` 审计日志。

## 1.8.0 - 2026-10-27

### Added

- **记忆功能**：AI 可以在 `memory/` 目录里新建、追加、覆写纯文本笔记，跨对话保存。典型场景是玩家托付「某人上线时提醒某事」——这类约定以前只活在最近若干条上下文里，消息一多就忘了，现在可以写进记忆文件长期保留。
- 新增工具 `memory_read`（不填 `file_name` 时返回文件清单）与 `memory_write`（`mode=append` / `overwrite`）。
- 单个记忆文件有字节上限（`memory_max_file_kb`，默认 8 KB）。写入超限时**不静默截断、也不写坏半截文件**，而是回一段指引，让模型自己读出全文、压缩成要点再覆写；用到上限 80% 时会提前提示。
- `config.json` 升到 v5，新增 `memory_enabled`（默认 `true`，开箱即用）、`memory_dir`、`memory_max_file_kb`。旧配置自动合并升级并备份为 `.v4.bak`。

### Changed

- **工具层重构**：新增 `tool` 包（`Tool` 接口 + `Tools` 注册表 + `ToolRun` 单次会话状态），把原先硬编码在 `OpenAiClient` / `AnthropicClient` 里的知识库读取与联网搜索搬进独立实现类。协议层只负责把工具 schema 翻译成各家格式（OpenAI 的 `function.parameters`、Anthropic 的 `input_schema`）并回灌结果；新增能力只需加一个 `Tool` 实现，两家客户端都不用改。
- Anthropic 通道补齐多轮工具循环与 `web_search` 支持（此前只支持读一次知识库，不支持搜索、也不支持多轮）。
- 记忆规则由模组在读取提示词时追加到末尾，**不需要修改自己的 `system_prompt.txt`**；judge 提示词不会追加（它不发送工具）。
- `memory_enabled: true`（默认）会顺带打开工具通道：即使 `enable_tools` 仍是 `false`，也会发送记忆工具定义。该状态下 `use_json_mode` 不生效（带工具时部分平台不返回 `tool_calls`，与 1.7.0 的既有取舍一致）。想完全保持 1.7.0 老行为（不发工具、保留 `response_format`）时把 `memory_enabled` 设为 `false`。

### Behavior

- 记忆目录默认 `config/aichat/memory/`，配置加载时自动创建。
- 文件名白名单：纯文件名（不带目录、不能含 `..`）、只允许字母数字/中文/`_`/`-`/`.`/空格、扩展名限定 `.md` / `.txt`（缺省补 `.md`），长度上限 64 字符；路径越界直接拒绝。写了别的扩展名（`.exe` 之类）直接报错，不会静默改名。
- 记忆文件数量软上限 32 个：达上限后拒绝新建，只能覆写或合并进已有文件，防止被反复刷文件撑爆目录。
- 每次写入记录一条 INFO 日志（文件名 + 体积 + 上限）；`/aichat memory` 查看当前清单。
- 大小上限只在写入端生效：手工放进 `memory/` 的更大文件不会被模组改写或删除，只是清单里会显示真实体积。

## 1.7.0 - 2026-10-26

### Added

- **联网搜索**：新增 `web_search` 工具。模型需要外部实时信息（新闻、天气、比分、价格等）时调用，模组去搜索接口取结果再回灌给模型。搜索由模组执行，不依赖模型平台是否提供服务端搜索——中转站、DeepSeek、GLM、Kimi 等一律可用。
- 搜索后端可选：`bing`（默认，抓 cn.bing.com，零 Key、国内直连）、`searxng`（自建实例，零费用）、`tavily` / `bocha` / `zhipu`（商业 API，需 Key）。
- 新增配置项：`search_enabled`、`search_provider`、`search_api_url`、`search_api_key`、`search_max_results`、`search_timeout_seconds`。

### Changed

- `config.json` 升到 v4，升级时自动备份并补齐搜索相关字段。旧配置默认 `search_enabled: true` + `search_provider: "bing"`，升级后即可用，不需要额外 Key。
- 工具分派从「只认 `read_knowledge_file`」改成按 `function.name` 分派，知识库与搜索共用同一套多轮循环。
- 默认 `system_prompt.txt` 增加搜索使用规则（第 9、10 条）。

### Behavior

- 一次对话最多搜索一次；搜索失败不重试，把原因回灌给模型并写 WARN 日志，由模型照实告诉玩家「没搜到」，避免编造。
- 搜索结果总长度上限 4000 字符、单条摘要 200 字符，防止吃掉 prompt 预算。
- `search_enabled: false` 或 provider 缺 Key 时不注册工具，行为与 1.6.2 一致。

## 1.6.2 - 2026-10-22

### Changed

- 启动日志改为 `AI Chat v<版本> 已加载，配置目录: ...`，排查「服务器上跑的是哪个 jar」时不用再猜。

## 1.6.1 - 2026-10-22

### Fixed

- 中文 Windows（GBK 控制台）下，AI 回复里的颜文字常含 GBK 表示不了的字符（`｡`、`・`、`∀`、`ﾟ`、`♪`、`๑`、`❤` 等）。这些字符写进控制台时会让 MCDR 等包装器抛 `UnicodeEncodeError: 'gbk' codec can't encode character`，严重时 MCDR 主循环直接 CRITICAL 刷屏。现在按 `sun.stdout.encoding` / `native.encoding` 判断控制台编码，只对**控制台那份**做处理：广播给控制台时丢掉表示不了的字符，debug 日志里转义成 `\uXXXX`；玩家聊天框走网络 UTF-8，仍是完整颜文字。UTF-8 控制台不受影响。

## 1.6.0 - 2026-10-22

### Fixed

- 部分中转站 / 兼容端点会把 `system` 角色整条丢弃（实测：系统消息里放校准码，模型答「未提供」；`response_format` 也会被静默忽略）。此时模组的 JSON 格式契约、知识库文件列表、人设全部到不了模型，表现为模型输出散文和 markdown、判断永远失败。新增 `system_prompt_in_user`（默认 `true`）：把系统提示词再拼进最后一条用户消息，并用【当前事件】标记真正要回应的内容。
- 工具描述里补上知识库真实文件名。此前只写「必须是可用知识库文件列表中的文件名」而不给列表，模型只能瞎猜（`小墨.md`、`knowledge.md`、`file_list` 之类），全部读取失败。
- 工具调用从「只处理 1 轮、只取第一个 tool_call」改成多轮循环（默认最多 4 轮），每轮把所有 `tool_call` 结果都回灌；超过上限后再发一次不带工具的请求，逼模型直接给结论。此前第二轮又返回 `tool_calls` 时 `content` 为空，整条消息被丢弃。
- 单个知识库文件回灌超过 6000 字符时截断，避免长文件吃掉大量 prompt token。
- 推理模型（如 `glm-4.7-flash`）作为判断模型时 `content` 为空、AI 永远不回复：判断模型默认 `max_output_tokens` 从 256 提高到 1024，推理 token 同样占用该预算。
- 遇到 `finish_reason=length` 且 `content` 为空时，自动把 `max_tokens` 放大（至少 1024，上限 4096）重试一次，重试失败才按不回复处理并给出明确告警。
- 判断模型在 GLM 平台（`bigmodel.cn` / `z.ai` / `glm-*`）上自动发送 `"thinking": {"type": "disabled"}`，避免思考把输出预算吃光；`glm-5.3` 系列强制思考，不走该逻辑。

### Changed

- `config.json` 升到 v3，升级时自动备份并补齐 `system_prompt_in_user`。
- 判断模型返回空内容时的警告补充了排查提示。
- README 补充模型兼容性说明与「AI 不回复」排查清单。

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
