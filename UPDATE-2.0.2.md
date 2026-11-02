# AIChat 2.0.2 更新说明

> 修一个 bug：**模型调完工具后回了话，但模组发不出去**（玩家端静默）；
> 外加三组新增：**`!!` 开头的指令类消息不再送给 AI**（第七节）、
> **省钱与节流开关（默认全关）**（第八节）、**安全加固 + `/aichat status`**（第九节）。
> 配置不用手改——`config.json` 会自动升到 v9（原文件按旧版本号备份，如 `config.json.v8.bak`，
> 并补上新字段与注释）；`system.json` 仍是 v2，提示词不用动。

## 一、现象

服务端日志长这样（真实复现，玩家 Senmo_awa 说了句「小墨在忙」）：

```
[debug] 工具调用 name=memory_read 结果长度=52 预算剩余=24524
[debug] 工具调用 name=read_knowledge_file 结果长度=500 预算剩余=24024
[debug] 工具调用 name=memory_read 结果长度=222 预算剩余=23802
[debug] 工具调用 name=memory_write 结果长度=33 预算剩余=23769
[debug] OpenAI 兼容响应 HTTP 200：finish_reason=stop
         content="收到喵～主人（森墨）忙的话，那我就安静待命，公屏不乱刷…"
WARN  AI 返回内容不是合法 JSON，已忽略：收到喵～主人（森墨）忙的话…
[debug] 解析结果：reply=false，content=null
```

模型**说话了**，但说的是自然语言，不是约定的 JSON，于是被整条丢弃 —— 玩家端一个字都没收到。

**第二条路径（同样被丢弃，一次工具都没调）：**

```
<Senmo_awa> 笨小墨，刚才那三个是bot啦
[debug] OpenAI 兼容响应 HTTP 200：finish_reason=stop
         content="哦哦，原来是机器人呀，我还在那儿煞有介事地"欢迎三位"呢，尴尬了……"
WARN  AI 返回内容不是合法 JSON，已忽略：哦哦，原来是机器人呀…
[debug] 解析结果：reply=false，content=null
```

## 二、根因

三个原因叠在一起：

1. **模型脱轨**：`deepseek-v4.1-flash` 连调 3 轮工具之后忘掉了 system 里的 JSON 契约，第 4 轮直接回人话。工具链越长越容易发生。
2. **没有契约提醒**：工具结果回灌之后就一路往下跑，中间再没人提醒「你该输出 JSON」。
3. **兜底覆盖不到这条路径**：2.0.1 的 `fallback_reply` 只在「content 为空」或「输出了私有工具标记（DSML）」时触发；「有内容但不是 JSON」是第三种情况，两个客户端（OpenAI 兼容 / Anthropic）都把它原样返回，最后死在 `AiClient.parseReply()` 的「不是合法 JSON，已忽略」上。

顺带一提：带工具时模组**不发送** `response_format=json_object`（工具调用与强制 JSON 互斥），所以工具链期间格式完全靠提示词撑着——这也是提醒值得补的原因。

## 三、修了什么

| 位置 | 改动 |
| --- | --- |
| 工具轮次（两家协议） | 每轮回灌工具结果后追加一条 `【系统提醒】…信息够了就只输出最终 JSON（{"action":"reply",...} 或 {"action":"ignore"}），不要再写自然语言段落；还缺数据就继续调用工具。`（约 45 token/轮） |
| 收尾检测 | 发现最终内容是**纯文本**（非空、非工具标记、也解析不出 JSON 对象）→ 进修复流程，而不是直接返回。**不限定有没有用过工具**：实测普通聊天同样会脱轨 |
| 脱轨修复轮（新增） | 原样带上模型那句话，补发一次「请把同样的意思改写成 JSON，不要调用工具」的请求；**只修一次** |
| 失败兜底（新增） | 修复后仍不是 JSON → 把原文包成 `{"action":"reply","content":"..."}` 走正常回复流程（也进历史），内容不再被丢弃；只有原文也不可用时才退回旧的 `fallback_reply` |
| 判断模型（双模型模式） | **不开**这套兜底：judge 只该回答 `reply`/`ignore`，把它的自然语言当成回复会乱说话，仍维持「非法 JSON = 不回复」 |
| 日志 | 脱轨时 WARN 打出原文预览与字数；修复成功时 INFO 一行，便于统计发生率 |

判定细节（`Fallbacks`）：

- 空内容 / 工具标记（`DSML`、`<|tool_call`、`<function_calls>`、`antml:invoke`、`｜｜`）→ **不**兜底，走原有 `fallback_reply` 逻辑。
- 本来就是合法 JSON 对象（含 ```json 代码块包裹）→ **不**兜底，走正常解析。
- 人话里带花括号（如「我看了一下 {配置} 是这样」）→ 算纯文本，正常兜底（不会因为一个花括号被误判成 JSON）。
- 残缺 JSON（如 `{"action":"reply","content":` 被截断）→ 算纯文本，兜底发原文，总比静默强。

## 四、升级方式

1. 替换 `mods/aichat-2.0.1.jar` 为 `aichat-2.0.2.jar`（**先删旧 jar，别两个都放**）。
2. 重启服务器。`config.json` 会自动升到 v9（原文件按旧版本号备份成 `config.json.v7.bak` /
   `config.json.v8.bak`），新增的 `ignored_chat_prefixes`（默认 `["!!"]`）与节流字段
   （默认全关）会带注释写进文件；`system.json` 与提示词不变。
3. 看到启动日志这三行说明配置生效了：

   ```
   忽略前缀: ["!!"]（以这些前缀开头的聊天不送 AI；[] = 不忽略）
   服务器管理工具: 关，特权前缀: "!+"，权限模式: op
   缓冲: 开（5 秒 / 10 条 / 500 字符），模型: xxx，工具: 开
   ```

4. 想确认生效：日志里工具轮次之后应出现 `[debug] 工具调用 … 预算剩余=…`，随后正常出回复。
5. 想确认 `!!` 拦截生效：开 debug 后发一句 `!!ledger size`，日志应出现
   `[debug] 忽略指令类消息，未提交给 AI：!!ledger size`，且不再有任何 HTTP 请求。

## 五、成本

- 常规对话：每轮工具调用多约 45 token 的提醒。
- 真脱轨时：多一次 HTTP 请求（约 1200 prompt token + 1~2 秒），换来一句格式干净的回复；修复失败也不会再静默。
- 不脱轨的对话（绝大多数）：零额外请求。

## 六、待实测清单

- [ ] 复现路径：连续两三轮工具调用（如「读一下玩家们」「看看玩家动态」）后模型仍能正常回话。
- [ ] 普通聊天（一次工具都不调）里模型回人话时也能正常发出，不再出现「AI 返回内容不是合法 JSON，已忽略」后静默。
- [ ] 日志出现 `模型没按 JSON 契约回复（纯文本 N 字），补发一次改写请求` 时，下一行应是 `改写请求成功，本轮按改写后的 JSON 回复`。
- [ ] 若修复也失败，玩家端应收到模型原话（不是「（这轮没理出结果，请再说一次）」）。
- [ ] `!+` 特权请求（`server_files`）走完工具链后同样能正常回复。
- [ ] Anthropic 协议侧（如主人在用）抽一次同样的验证。
- [ ] 发 `!!ledger size` 这类 MCDR 指令：不应产生任何 AI 请求（无 `请求失败` / 无 token 消耗），日志只有一行「忽略指令类消息」。调大缓冲期间也应如此（指令不能污染合并后的那条请求）。
- [ ] `daily_token_budget` 先设个小值（如 3000）连聊几句：日志出现「今日 token 已用 … / 预算 …，暂停 AI 请求」，之后不再有 HTTP 请求；`/aichat token clear` 后恢复。
- [ ] `player_cooldown_seconds` 设 10，连发两条：第二条日志出现 `玩家冷却中，忽略`，且无请求。
- [ ] `reply_only_when_mentioned` 开、`mention_names` 用默认：说「今天好热」不应答（debug 有「没被点名」），说「小墨在吗」正常应答。
- [ ] `!+ 读一下 server.properties`：应报「该路径被安全策略保护」，不返回任何内容。
- [ ] 让 AI 复述一段含 `password=abc12345` 的话：广播里应显示 `***`（脱敏生效）。
- [ ] `/aichat status` 的每一项与 `config.json` 实际值一致。

## 七、新增：`!!` 开头的指令类消息不再送给 AI

### 现象

MCDR（以及很多指令插件）的调用指令是走公屏的 `!!xxx`。模组以前把它当普通聊天处理，于是每敲一次 `!!ledger size` 就触发一次完整的 AI 请求：

```
<Senmo_awa> !!ledger size
<Server> [Command: /execute at @p run tellraw @a "[ledger] 数据库大小: 8.90GB"]
[aichat-worker/WARN]: AI 请求失败: java.net.SocketException: Connection reset   ← 白烧的一次请求
```

模型既看不懂 ledger 的输出，也不该看到它——纯粹浪费 token，还会把「玩家在让服务器执行指令」这个误解带进上下文。

### 怎么改的

- 新增配置字段 **`ignored_chat_prefixes`**（字符串数组，默认 `["!!"]`）。
- 命中前缀的消息在 `ChatListener` 里**直接丢弃**：不进缓冲区、不进历史上下文、不发 HTTP 请求。
  比「进了缓冲再过滤」更彻底——缓冲合并时这条指令也不会被拼进别人的话里。
- `AiDispatcher.submit()` 里另加一道同样的兜底判定（`!+` 特权请求不参与），将来新增事件入口也不会漏。
- 判定忽略**前导空格**（`"  !!ledger"` 同样算指令）；数组写成空 `[]` 表示「什么都不忽略」，回到旧行为。
- 想连 `!` / `.` 这类其他插件前缀一起忽略，直接往数组里加：

```json
"ignored_chat_prefixes": ["!!", "!"],
```

> 注意 `!+`（特权前缀）不受影响：它的判定在忽略判定之前，且 `!+` 不以 `!!` 开头，两条通道互不干扰。

## 八、省钱与节流（新开关，默认全部关闭）

三个开关都在 `AiDispatcher.submit()` 入口判定——命中即返回，**不进缓冲、不进历史、不发 HTTP 请求**。
默认值刻意保持「不配 = 旧行为」，主人不主动开就不会有任何行为变化。

| 字段 | 默认 | 作用 | 命中时的表现 |
|---|---|---|---|
| `player_cooldown_seconds` | `0`（关） | 同一玩家两条消息的最小间隔 | 冷却期内的消息丢弃，debug 日志 `玩家冷却中，忽略：xxx` |
| `daily_token_budget` | `0`（不限） | 全局每天可用 token 上限 | 用尽后**所有** AI 请求暂停（含 join/quit），每小时最多一条 WARN，跨天自动恢复 |
| `reply_only_when_mentioned` | `false` | 被动回复：只在被点名时接话 | 没点名的消息零 token 忽略，debug 日志 `没被点名（被动回复），忽略：xxx` |
| `mention_names` | `["小墨"]` | 点名关键词（大小写不敏感，可带 `@`） | 留空会回落默认值，不会把 AI 变成哑巴 |

**建议的上手顺序**：先只开 `daily_token_budget`，给个宽松值（比如 200000），跑几天用
`/aichat status` 看实际日均，再收紧到日均的 1.5 倍。冷却和被动回复影响聊天体验，确认预算不够再说。

`daily_token_budget` 的统计口径就是 `/aichat token` 那套 `usage.jsonl`（按本机时区归日），
带 30 秒缓存 + 每次写入后失效，所以判定最多滞后一条请求。

## 九、安全加固与状态面板

### 1. 内置拒绝清单（改配置也关不掉）

`server_tools_deny_paths` 是可配置的默认清单，主人把数组清空就等于把密码递给 AI。所以另加一份
**硬编码**的拒绝清单，`server_files` 读写都会先过它：

```
server.properties      ← 里面有 rcon 密码
ops.json / whitelist.json / banned-players.json / banned-ips.json / usercache.json
config/aichat          ← 整个配置目录（API Key 在这里）
```

要放宽只能改代码。命中时的报错文案改成了「该路径被安全策略保护（内置拒绝清单 /
server_tools_deny_paths）」，方便区分是哪一层挡的。

### 2. 出口脱敏 `redact_secrets`（默认开）

广播**之前**，把 AI 回复里像密钥的内容替换成 `***`：

- `sk-xxxxxxxx` → `sk-***`
- `Authorization: Bearer xxxxxxxx` → `Bearer ***`
- `password=xxx` / `api_key: xxx` / `secret=xxx` → `***`

这是第二道闸：拒绝清单管「不让读」，脱敏管「读了也不能说」。只抹明显像密钥的形状，
不做通用高熵检测（会把玩家聊哈希、Base64 的正常内容也吃掉）。觉得误伤可以设 `false` 关掉。

### 3. `/aichat status`（管理员）

一屏输出当前真正生效的东西，排查「配置写了没生效」不用再发消息试探：

```
—— AI Chat 状态 ——
回复：开｜debug：关｜历史：15 条｜缓冲待发：0 条
模型：deepseek-flash｜协议：auto｜双模型：关｜工具：开
今日 token：12345 / 预算 100000（剩 87555）｜今日请求 42 次
冷却：关｜被动回复：关｜忽略前缀：["!!"]｜密钥脱敏：开
```
