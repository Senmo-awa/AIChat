# Changelog

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
