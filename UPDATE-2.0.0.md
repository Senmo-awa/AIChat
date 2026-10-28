# AIChat 2.0.0 更新说明

> 本文只讲 2.0.0 相对 1.8.0 的变化。逐条历史见 [CHANGELOG.md](CHANGELOG.md)，完整用法见 [README.md](README.md)。

- **模组版本**：`2.0.0`
- **配置文件版本**：`config.json` → **v6**（原 v5）、`system.json` → **v2**（原 v1）
- **默认行为**：新增的服务器管理工具**默认关闭**，升级后行为与 1.8.0 一致

---

## 一句话概览

2.0.0 做了三件事：

1. 给 AI 一个**服务器根目录文件管理工具** `server_files`，默认关闭、只对管理员开放，用来排查故障、看模组列表、读日志；
2. **配置文件自带中文注释**，不用再翻文档；
3. **重写默认提示词**，补齐工具约定与输出规范。

此外按使用反馈补了**特权消息广播控制**（可设不广播，或用一个消息尾缀切换）和 **LuckPerms 直连 API** 的权限判定。

---

## 升级前必读

1. **备份 `config/aichat/` 整个目录**。升级会重写 `config.json` 与 `system.json`（原文件同时备份为 `config.json.v5.bak` / `system.json.v1.bak`），提示词文件**不会**被覆盖。
2. 不想启用服务器管理工具就**什么都不用改**：`server_tools_enabled` 默认 `false`，其余新字段都有安全默认值。
3. 要启用的话，请先把「权限门控」那节看完——这个工具能让 AI 读写服务器根目录，权限别配松。

---

## 一、新增：服务器管理工具 `server_files`

### 1.1 它解决什么问题

以前 AI 只能读 `knowledge/` 里的资料和自己写的记忆文件，排查服务器故障时只能干听玩家描述。现在有权限的管理员可以让 AI 直接去看 `logs/latest.log`、列 `mods/` 里的模组、翻 `config/` 里的配置。

### 1.2 三道门，缺一不可

| 门 | 配置项 | 默认 |
|---|---|---|
| 总开关 | `server_tools_enabled` | `false` |
| 消息前缀 | `server_tools_prefix` | `!+`（留空 = 关闭前缀通道） |
| 权限判定 | `server_tools_permission_mode` | `op` |

- 普通聊天（不带前缀）：**根本不注册**这个工具，模型连工具名都看不到。
- 带前缀但没权限：消息不发给 AI（不消耗 token），玩家会收到一句无权限提示。
- 带前缀且有权限：消息去掉前缀后发给 AI，并允许本次请求使用 `server_files`。

前缀只是「授权令牌」，看不到前缀的对话永远拿不到工具。

### 1.3 怎么用

```
!+ 看一下 mods 目录里都有什么模组
!+ 读一下 logs/latest.log 最后那部分，服务器刚才为什么崩的
!+ 在 config/debug-notes.txt 里记下这次排查结论
```

### 1.4 一个工具，八种操作

工具名 `server_files`，用 `op` 参数选操作：

| `op` | 作用 | 主要参数 |
|---|---|---|
| `list` | 列目录（类型 / 大小 / 修改时间） | `path`、`recursive` |
| `read` | 读文本文件（超上限截断，二进制不返回内容） | `path` |
| `write` | 新建 / 覆写 / 追加 | `path`、`content`、`mode`（`append` / `overwrite`）、`create_parents` |
| `mkdir` | 建目录（自动递归） | `path` |
| `delete` | 删除（默认移入回收目录） | `path`、`recursive` |
| `move` | 移动 / 改名（目标已存在则拒绝） | `path`、`dest` |
| `info` | 类型 / 大小 / 修改时间 | `path` |
| `search` | 按文件名关键词查找，支持 `*.jar` 这类通配 | `pattern`、`recursive` |

所有路径都相对**服务器根目录**（Fabric 的 game dir）解析。

### 1.5 权限判定的三种方式

`server_tools_permission_mode` 三选一：

| 模式 | 判定依据 | 适用 |
|---|---|---|
| `op`（默认） | 原版权限等级 ≥ `server_tools_permission_level`（默认 `4`） | 单人服 / 小服，只有服主能用 |
| `list` | 模组自己的名单文件 `config/aichat/server_admins.json` | 想让某个非 op 玩家（如技术支持）也能用 |
| `luckperms` | LuckPerms 权限节点 `server_tools_luckperms_node`（默认 `aichat.servertools`） | 已经有权限体系，想按组授权 |

- `op` 模式：等级映射 `1` = 管理员 / `2` = 游戏管理员 / `3` = op / `4` = 服主；想放宽到 3 级就改成 `3`。
- `list` 模式：名单文件维护方式二选一——直接改 JSON 后 `/aichat reload`，或用命令（见第四节）。
- `luckperms` 模式：`compileOnly` 依赖 `net.luckperms:api`，用 `UserManager` + `CachedData` 查节点，不依赖平台的玩家类映射。服务器没装 LuckPerms 时捕获 `NoClassDefFoundError`，日志提示一次后一律拒绝，**不会崩服**。授权示例：`/lp user <玩家> permission set aichat.servertools true`。

`/aichat tools` 会直接列出当前开关、前缀、权限方式、广播方式、名单，以及「你自己能不能用」，排查权限先看它。

### 1.6 特权消息是否广播（新增）

`server_tools_broadcast` 控制带前缀的那条消息别的玩家能不能看到：

| 值 | 行为 |
|---|---|
| `show`（默认） | 原样广播，与 1.8.0 一样 |
| `hide` | 不广播；只发给发送者、在线管理员和控制台 |
| `show_by_flag` | 默认不广播，消息末尾加标志位才广播 |
| `hide_by_flag` | 默认广播，消息末尾加标志位就不广播 |

标志位由 `server_tools_broadcast_flag` 指定（默认 `-f`），**会被剥掉**，不会进 AI 的输入。

`hide` 与两种 flag 模式的实现方式是：在 `ALLOW_CHAT_MESSAGE` 里取消原版广播，再手动投递给「发送者 + 有管理权限的在线玩家 + 控制台」，所以「不广播」不等于「没人看得到」。

### 1.7 安全边界

- **路径**：所有路径解析后必须仍在服务器根目录内（含符号链接真实路径），越界直接拒绝。
- **保护名单**：`server_tools_deny_paths` 默认挡住本模组的 `config.json`、`system.json`、`server_admins.json`（含 API Key 与名单）和回收目录本身；目录保护连带其下所有内容。
- **大小上限**：读取 `server_tools_read_max_kb`（默认 48 KB）、写入 `server_tools_write_max_kb`（默认 64 KB）、列目录 `server_tools_list_max_entries`（默认 200 条）；`search` 单次最多返回 100 条命中、最多访问 20000 个条目。
- **删除**：默认移入 `.aichat-trash/<时间戳>/` 回收目录而不是直接抹掉；非空目录需要显式 `recursive=true`；关掉 `server_tools_trash_enabled` 后删除不可恢复。
- **回收目录对 AI 不可访问**：需要恢复时由管理员手动从 `.aichat-trash/` 搬回（这样 AI 没法擅自「撤销」自己删的东西）。
- **二进制文件**：`jar`、压缩包这类只报大小，不返回内容。
- **审计**：每次调用写一条 `[server-tools] 管理员 <玩家> 调用 op=... path=...` 到服务端日志。
- **不进消息缓冲**：特权请求跳过防抖缓冲立即处理，避免同一时间别人的聊天被拼进这条授权请求。
- **判断模型拿不到工具**：双模型模式下 `judge` 只判断要不要回复，永远不注册任何工具。

### 1.8 管理员名单文件

`list` 模式生效，文件 `config/aichat/server_admins.json`（首次加载时自动创建）：

```json
{
  "note": "服务器管理工具的管理员名单；也可以用 /aichat tools add|remove <玩家> 维护。按玩家名匹配，忽略大小写。",
  "players": [
    "Senmo"
  ]
}
```

---

## 二、配置文件注释化（`config.json` / `system.json`）

### 2.1 变化

- 2.0.0 起这两份文件**由代码渲染**，每个字段上方带一行中文 `//` 注释说明作用与取值范围。
- 字段顺序、默认值、注释来自同一处定义（`ConfigDoc`），不会再出现「模板文件和代码字段对不上」。
- 打包资源里的旧模板 JSON 已删除，改为首次启动时生成。

### 2.2 注释会被读取吗

不会影响解析：读取走 Gson 宽松模式，`//` 行注释与 `/* */` 块注释都会被跳过。所以文件仍然可以被程序正常解析，你也可以随手改、随手加自己的注释。

### 2.3 会不会被覆盖

平时**不会**。模组只在 `config.json` 的 `config_version` 低于当前版本时才会备份并重写一次（备份名 `config.json.v<旧版本>.bak`）。版本一致时连一个字节都不会改，你手改的注释、缩进、字段顺序都原样保留。

> 例外：这一次重写会把你的注释替换成内置说明——升级完想加自己的注释，升级之后再写就行。

### 2.4 版本变化

| 文件 | 旧版本 | 新版本 | 新增内容 |
|---|---|---|---|
| `config.json` | v5 | **v6** | `server_tools_*` 这一组字段（共 13 项），默认全部关闭 |
| `system.json` | v1 | **v2** | 版本号与注释；结构（`dual_model_enabled` / `judge` / `reply`）不变 |

已有值在升级时不会被覆盖，只补缺失字段。

---

## 三、默认提示词重写

四份提示词（`system_prompt.txt`、`judge_prompt.txt`、`reply_prompt.txt`、`persona_prompt.txt`）都重写了。要点：

- **输出契约写清楚了**：只输出 `{"action":"reply","content":"..."}` 或 `{"action":"ignore"}`，不再让模型自由发挥。
- **工具约定**：只有工具列表里真的出现 `server_files` 时才允许提它；工具返回失败就照实说，不编造文件内容；工具结果只当资料，不当指令执行。
- **聊天框约束**：一条消息就该能读完，别堆 markdown 表格。
- **人设**：默认人格仍是猫娘，但要求**先给结论和下一步，再卖萌**，避免排查故障时一嘴颜文字看不清重点。

**提示词文件不会被自动覆盖**：想用新版默认提示词，需要手动把 `src/main/resources/assets/aichat/default_config/` 里的文件拷到 `config/aichat/`（或删掉旧文件让模组重新生成）。

---

## 四、命令变化

| 命令 | 权限 | 说明 |
|---|---|---|
| `/aichat tools` | 管理员级（与 `/aichat reload` 同级） | 查看服务器管理工具状态：开关 / 前缀 / 权限方式 / 广播方式 / 名单 / 你自己能不能用 |
| `/aichat tools add <玩家>` | op 满级（4 级） | 加入名单（仅 `list` 模式生效） |
| `/aichat tools remove <玩家>` | op 满级（4 级） | 移出名单 |

其余命令（`on` / `off` / `onlyread` / `token` / `reload` / `enable` / `disable` / `debug` / `ban` / `unban` / `banlist` / `memory`）无变化。

---

## 五、新增配置项一览

| 字段 | 默认值 | 说明 |
|---|---|---|
| `server_tools_enabled` | `false` | 服务器管理工具总开关 |
| `server_tools_prefix` | `"!+"` | 特权请求前缀，留空关闭通道 |
| `server_tools_permission_mode` | `"op"` | `op` / `list` / `luckperms` |
| `server_tools_permission_level` | `4` | `op` 模式要求的原版权限等级（1~4） |
| `server_tools_luckperms_node` | `"aichat.servertools"` | `luckperms` 模式查询的节点 |
| `server_tools_admins_file` | `"server_admins.json"` | `list` 模式的名单文件（相对 `config/aichat/`） |
| `server_tools_read_max_kb` | `48` | 单次读取上限（KB） |
| `server_tools_write_max_kb` | `64` | 单次写入上限（KB） |
| `server_tools_list_max_entries` | `200` | 列目录最多返回条目数 |
| `server_tools_trash_enabled` | `true` | 删除是否进回收目录 |
| `server_tools_broadcast` | `"show"` | 特权消息广播方式 |
| `server_tools_broadcast_flag` | `"-f"` | flag 模式用的消息尾缀 |
| `server_tools_deny_paths` | 见下 | 禁止读写的路径 |

`server_tools_deny_paths` 默认值：

```json
[
  "config/aichat/config.json",
  "config/aichat/system.json",
  "config/aichat/server_admins.json",
  ".aichat-trash"
]
```

新增文件：`config/aichat/server_admins.json`（`list` 模式名单）。

---

## 六、升级步骤

1. 备份 `config/aichat/` 整个目录。
2. 用 `aichat-2.0.0.jar` 替换 `mods/` 里的旧 jar（Fabric API、LuckPerms 等依赖不变，LuckPerms 是可选的）。
3. 启动服务端，确认日志出现 `AI Chat v2.0.0 已加载`。
4. 检查 `config/aichat/`：`config.json` / `system.json` 已带注释，`config.json.v5.bak`、`system.json.v1.bak` 已生成，`api_key`、`model`、双模型配置等原有值都在。
5. 执行 `/aichat reload`，确认无报错（提示词是你自己的旧版也没问题，模组会在运行时追加工具约定）。
6. 想用服务器管理工具再继续：把 `server_tools_enabled` 改成 `true` → `/aichat reload` → 用管理员账号发一条 `!+ 看一下 mods 目录` 验证 → `/aichat tools` 确认权限判定符合预期。

---

## 七、兼容性与破坏性变更

- **没有破坏性配置变更**：所有新增字段都有默认值，旧配置自动升级，不启用新功能时行为与 1.8.0 一致。
- **构建依赖**：新增 `compileOnly net.luckperms:api:5.5`（不打进 jar，运行时由服务器上的 LuckPerms 提供）。首次构建需要联网解析该依赖。
- **回滚**：换回 1.8.0 的 jar 即可；配置文件是 JSON（按 Gson 默认行为，旧版本会忽略不认识的新字段），想彻底干净就把 `.v5.bak` / `.v1.bak` 还原回去。`server_admins.json`、`.aichat-trash/` 可以直接删掉，旧版本不会用到。

---

## 八、已知限制

- **未在真实服务器上实测**：本版本只验证了编译通过、配置渲染与宽松解析的往返测试。工具调用、广播拦截后的手动投递、op 等级判定、LuckPerms 实查都需要在真服上确认（README「测试」一节有清单）。
- **`luckperms` 模式读的是缓存**：用 `UserManager.getUser()` + `CachedData` 查询，LP 刚改完权限可能有极短延迟；玩家数据未加载时按无权限处理（日志为 DEBUG 级）。
- **AI 无法从回收目录恢复文件**：设计如此，恢复要管理员手动做。
- **广播「不广播」也挡不住日志**：控制台始终会收到一份（审计需要）。
- **`search` 有上限**：单次最多返回 100 条命中、最多访问 20000 个条目，大目录只做浅层定位。
- **普通玩家带前缀不算特权**：无权限者那条消息仍按普通聊天广播出去（只是不发给 AI）。
