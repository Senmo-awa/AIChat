# Changelog

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
