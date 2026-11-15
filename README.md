# AI Chat

给你的 Minecraft 服务器请一位常驻的 AI 伙伴：它认得每位玩家，懂你服务器的人设与规矩，回答问题、欢迎新人，被授权后还能翻日志、查模组、帮你排查故障。

接得上 OpenAI 兼容和 Anthropic 两类接口——DeepSeek、千问、Kimi、智谱、豆包、Gemini、OpenRouter，或者本机跑的 Ollama 都能用。

> 适配 Minecraft 26.1.2 · Fabric Loader 0.19.4+ · Fabric API · Java 25

---

## 安装

1. 把 `aichat-<版本>.jar` 和对应版本的 Fabric API 放进服务端 `mods/`。
2. 启动一次服务器，`config/aichat/` 会自动生成配置与提示词。
3. 打开 `config.json`，填上 `api_key`，按需改 `model`，执行 `/aichat reload`。

进服打句话，它就会回你。

配置文件全部自带中文注释，每一项是干什么的、默认值多少，文件里都写着，不用翻文档。改完 `reload` 即可，不必重启服务器。

## 它能做什么

| 能力 | 说明 | 默认 |
|---|---|---|
| 聊天对话 | 玩家说话它就接话，带最近对话上下文，不重复、不复读 | 开 |
| 欢迎与送别 | 新玩家进服打招呼，有人离开时按语境决定要不要说一句 | 开 |
| 知识库问答 | 把你的服务器介绍、规则、玩法放进 `knowledge/`，它照着回答，不瞎编 | 可开 |
| 联网搜索 | 问新闻、天气、比分、版本更新时自己上网查 | 可开 |
| 长期记忆 | 记住玩家说过的事，下次还认得 | 开 |
| 私聊对话 | `/aichat pm` 单独问它，回复只有自己看得到，不打扰公屏 | 仅管理员 |
| 服务器管理 | 看日志、列模组、读配置、查目录，帮忙排查故障 | **关** |

默认状态下它只是个会聊天的管理员：不碰文件、不执行指令、不假装成玩家。

## 让它更懂你的服务器

- **人设**：`persona_prompt.txt` 写它的性格和说话方式，改完 `reload` 就换一副嗓音。
- **规矩**：`system_prompt.txt` 是它的行为准则，什么时候该接话、什么该忽略，都能自己调。
- **知识库**：把 `md`/`txt` 丢进 `knowledge/`，它就照着回答。适合放服务器介绍、玩法说明、常见问题。

## 服务器管理（默认关闭）

开着这一项，它才真正能帮你干活：`list` 看目录、`read` 读文件、`info` 看大小、`search` 找文件，甚至写文件、建目录、移动和删除（删除默认进回收目录，不是直接抹掉）。

安全上做了三道锁，一定要看清再开：

1. **总开关**：`server_tools_enabled` 默认 `false`，不主动开它什么都不做。
2. **管理员前缀**：只有以 `!+` 开头的消息才触发，普通聊天永远不会动到文件。
3. **权限判定**：按 OP 等级、名单或 LuckPerms 判断谁能用。

另外内置了一份拒绝清单（`server.properties`、`ops.json`、`whitelist.json`、`banned-*`、`usercache.json`、`config/aichat`），改配置也关不掉——API 密钥和封禁名单不会被念到公屏。广播回复前还会自动抹掉 `sk-` 开头的密钥和 `password=` 之类的片段。

## 多模型（进阶）

想让「谁该接话」和「谁来说话」分工，或者让几位性格不同的 AI 同时在场，就打开多模型：

1. 把 `system.json` 的 `multi_model_enabled` 改成 `true`。
2. 在同一个文件的 `models` 里写清楚每个模型：`id`（唯一名字）、`role`（`judge` 判断 / `chat` 回复 / `admin` 管理员请求）、`routes_to`（判断通过后交给谁）。
3. `/aichat reload`。模组会在 `config/aichat/models/<id>/` 下自动生成一份带注释的 `model.json`，填上各自想用的模型名或 Key 即可——留空就继承主配置。

- 多个 `chat` 模型会同时发言，各带自己的 `reply_prefix`，一眼能分清是谁说的。
- 打开 `reply_to_ai` 的模型会接其它 AI 的话，轮数由 `ai_dialogue_max_rounds` 限制（默认 2，最后一轮会自动提示它收尾），不会无限对聊。
- 没有 `judge` 就每条消息都回；没有 `admin` 则管理员请求退回交给 `chat`——少配一个角色不会让 AI 哑掉。
- `/aichat models` 看编排状态，`/aichat token model` 看每个模型各花了多少 token。

## 常用命令

所有玩家：

| 命令 | 作用 |
|---|---|
| `/aichat on` | 正常参与（默认） |
| `/aichat off` | 既不发给 AI，也看不到 AI 说话 |
| `/aichat onlyread` | 自己不发给 AI，但能看 AI 说话 |
| `/aichat pm <内容>` | 私聊问它一句，回复只有自己看得到（范围见 `private_chat_mode`） |

管理员（OP）：

| 命令 | 作用 |
|---|---|
| `/aichat reload` | 改完配置热重载 |
| `/aichat status` | 一屏看当前状态：模型、开关、缓冲、今日用量 |
| `/aichat token` | 最近 7 天 token 用量（公屏与私聊分开显示） |
| `/aichat token pm` | 私聊用量按玩家明细 |
| `/aichat token model` | 按模型分类的用量（多模型） |
| `/aichat models` | 多模型编排状态 |
| `/aichat enable` / `disable` | 全局开关 AI 聊天 |
| `/aichat debug` | 排查用：聊天框显示用量，接口原始返回写进日志 |
| `/aichat tools` | 服务器管理工具的状态与权限自查 |
| `/aichat ban <玩家>` | 禁止某位玩家使用 AI |

## 最常调的几个设置

| 设置 | 作用 | 默认 |
|---|---|---|
| `api_url` / `api_key` / `model` | 用哪个接口、哪把钥匙、哪个模型 | DeepSeek |
| `enable_thinking` | 用推理模型时是否开思考。模型回复总是空白可以关掉它 | 开 |
| `buffer_delay_seconds` | 聊天防抖：连发几句合并成一次提问，省钱 | 5 |
| `max_context_messages` | 带多少条历史给模型 | 15 |
| `reply_only_when_mentioned` | 只在被点名（默认「小墨」）时才回话 | 关 |
| `daily_token_budget` | 每天 token 上限，超了自动停，跨天恢复 | 不限 |
| `ignored_player_prefixes` | 名字这些开头的当成假人忽略 | `bot_` |
| `private_chat_mode` | 私聊范围：`off` 关闭 / `admin` 仅管理员 / `all` 所有人 | `admin` |
| `server_tools_enabled` | 服务器管理工具总开关 | 关 |

## 常见问题

**它完全不说话？**
先看 `config.json` 里的 `api_key` 是否填了、`model` 名字对不对，再开 `/aichat debug` 看日志里的接口返回。常见原因是模型不支持 JSON 输出，或者推理模型把答案写进了思考字段——后者把 `enable_thinking` 关掉即可。

**它太吵 / 只想被叫到时说话？**
打开 `reply_only_when_mentioned`，只有在聊天里提到 `mention_names` 里的词它才接话。

**换模型或换接口？**
改 `api_url` 和 `model` 后 `reload`。`auto` 会根据地址自动识别协议，识别不准就手动填 `openai` 或 `anthropic`。

**配置文件会不会被覆盖？**
不会。只有配置版本落后于模组版本时，才会先备份成 `config.json.v<旧版本>.bak` 再补上新字段。你自己加的注释、空行、顺序都保留。

**升级要注意什么？**
换 jar 重启即可，配置自动迁移。版本之间的改动都写在 [CHANGELOG](CHANGELOG.md) 和对应 Release 说明里。

## 从源码构建

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

产物在 `build/libs/aichat-<版本>.jar`。

## 许可证

MIT，详见 [LICENSE](LICENSE)。
