# 客户端与服务端接口规范

本文以当前 `app/src/main` 和 `backup-server/backup_server` 实现为准。客户端与自建备份服务之间只有 HTTPS REST API；库存查询、写入、提醒和撤销在客户端本地完成，没有远程库存 API。

## 1. 自建备份服务通用约定

- 基址：`https://backup.example.com/api/v1`（示例地址，须由部署者替换）。客户端要求 HTTPS、无 URL 内嵌账号密码，路径必须以 `/api/v1` 结尾；带 Bearer 的请求不跟随重定向。
- 鉴权：所有 `/api/v1/*` 请求使用 `Authorization: Bearer <device-token>`。令牌由服务端 CLI 创建和撤销，不提供公网注册或发令牌接口。401 表示缺失/无效，403 表示已撤销。
- `backup_id` 为小写规范形式的 UUID v4。哈希为文件原始字节的 64 位小写 SHA-256；时间是 UTC ISO 8601 字符串；大小均为字节。一次上传最大值默认 500,000,000 字节，服务端可配置；客户端固定最多 500,000,000 字节。
- 成功响应为 JSON，下载例外。失败响应：`{"code":"ERROR_CODE","message":"...","requestId":"..."}`；头部 `X-Request-ID` 用于排查。429 附 `Retry-After` 秒数。HTTP 502/504 可由反向代理转换为 503 `BACKEND_UNAVAILABLE`。
- 服务端按账户保留最近 30 份（可配置），同一备份 ID 同一内容重复上传返回 200，不同内容返回 409。服务端备份是**明文 JSON**。

| 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/v1/capabilities` | Bearer | 200：`apiVersion`, `maxUploadBytes`, `maxRequestBytes`, `chunkUpload`, `retentionCount`, `serverTime` |
| PUT | `/api/v1/backups/{backup_id}` | `Content-Type: application/json; charset=utf-8`、`Content-Length`、`X-Content-SHA256`；body 为完整备份 JSON | 201 新建/200 幂等：`backupId`, `sizeBytes`, `sha256`, `receivedAt` |
| GET | `/api/v1/backups?limit=30&cursor=...` | `limit` 1–100；`cursor` 为服务器返回的不透明分页值 | 200：`{"items":[BackupEntry],"nextCursor":null|string}` |
| GET | `/api/v1/backups/{backup_id}` | Bearer | 200：备份 JSON 字节流，含 `Content-Length` 和 `X-Content-SHA256` |
| PUT | `/api/v1/uploads/{backup_id}/chunks/{index}` | `application/octet-stream`、`Content-Length`、`X-Upload-Length`、`X-Upload-SHA256`、`X-Chunk-SHA256` | 201 新块/200 重试：`backupId`, `chunkIndex`, `sizeBytes`, `sha256`, `alreadyComplete` |
| GET | `/api/v1/uploads/{backup_id}` | Bearer | 200：`backupId`, `totalSizeBytes`, `sha256`, `chunkCount`, `receivedChunks`, `expiresAt`, `completed`, `backup`；未建立会话时 404 |
| POST | `/api/v1/uploads/{backup_id}/complete` | Bearer；无业务 body | 201 新完成/200 已完成：`BackupEntry` |

`GET /healthz` 只接受本机回环地址请求，返回 `{"status":"ok"}`，不是公开客户端接口。

## 2. 客户端上传/下载流程

1. 连接测试调用 `capabilities`，要求服务端 `maxUploadBytes >= 500000000` 且 `retentionCount >= 30`。
2. 客户端导出 JSON v4，计算整文件 SHA-256。文件不超过 10,000,000 字节时用单次 PUT；更大时先取 `capabilities` 和上传状态，按固定 10,000,000 字节分块 PUT，再调用 `complete`。服务端 `maxRequestBytes` 必须至少 10,000,000 才能配合现有客户端分块。每块要求与预期索引、长度、单块及整文件哈希一致；断线后可查询 `receivedChunks` 续传。会话超时默认 24 小时。
3. 列表用 `limit=30` 逐页请求，客户端接受最多 10,000 条结果。下载时验证响应长度、SHA-256 和 JSON 内部 `backupId`，通过后才保存为可恢复文件。
4. 常见错误：400 参数/格式不合法，404 不存在或会话过期，409 ID/分块冲突，413 超限，422 哈希不符，429 限流，503 文件不可用，507 磁盘/配额不足。客户端按认证、冲突、容量、校验、限流和服务故障分类显示。

## 3. 备份文件 JSON v4

顶层字段：`schemaVersion: 4`、`backupId: UUID v4`、`createdAtUtc: string`、`reminderSettings: {enabled: boolean}`、`items: []`、`locationRecords: []`、`operations: []`。所有字段由客户端导出，服务端按字节保存并验证传输哈希，不解释物品业务内容。客户端仍可导入 v2/v3 备份，旧物品的补货提醒默认为关闭。

| 数组 | 每行字段 |
| --- | --- |
| `items` | `id`, `name`, `aliases`（JSON 数组字符串）, `category?`, `expiryDate?`, `lastUsedAt?`, `deletedAt?`, `createdAt`, `updatedAt`, `restockThreshold?`（`null` 关闭、`0` 只在用完时、正数为安全库存） |
| `locationRecords` | `id`, `itemId`, `locationCode`, `quantity`, `deletedAt?`, `createdAt`, `updatedAt` |
| `operations` | `id`, `seq`, `type`, `itemId?`, `locationCode?`, `quantity?`, `rawText?`, `asrText?`, `targetSeq?`, `batchId?`, `revokedAt?`, `createdAt` |

`?` 表示字段可为 JSON `null`，并非可随意省略。`operations` 是审计流水；撤销通过 `revokedAt` 标记，日常路径不删除历史。位置名是自由文本，协议不限定字母或数字格式。导入前客户端检查版本、记录关系、字段类型和大小上限。

## 4. 客户端调用的外部服务

这些是第三方协议，不由本仓库的服务端实现：

- **ASR**：客户端对配置的阿里百炼 WebSocket 地址发起连接，`Authorization: Bearer <ASR key>`。先发 `run-task` JSON（`task_group=audio`, `task=asr`, `function=recognition`, PCM 16 kHz），收到 `task-started` 后发送二进制 PCM 帧，结束发 `finish-task`，接收 `result-generated`、`task-finished` 或 `task-failed`。当前请求不发送库存热词 `vocabulary`。
- **LLM**：客户端向配置基址的 `/v1/chat/completions` POST JSON，`Authorization: Bearer <LLM key>`。请求含 `model`、system/user `messages`、`temperature:0`、`max_tokens:700`、`reasoning_effort:"none"`、`thinking:{"type":"disabled"}`、`response_format:{"type":"json_object"}`。主线路可选填写 OpenRouter 供应商标识；填写后请求增加 `provider:{"only":["供应商标识"]}` 和 `reasoning:{"effort":"none"}`，备用线路不使用此限制。中转服务需支持或透传这些参数。主线路失败时可尝试已配置的备用线路；每条线路总超时 10 秒、连接超时 1 秒。期望 `choices[0].message.content` 为 JSON 文本，格式为 `{"intent":"put|take|query|correct|delete|unknown","items":[{"name":"...","quantity":2,"locationCode":"..."}],"targetSeq":null,"note":null}`。客户端再次校验意图与库存，不直接信任模型结果。
- **坚果云 WebDAV**：使用官方 HTTPS WebDAV 地址与 Basic Auth（邮箱、应用密码），在应用专属目录使用 `MKCOL`、`PROPFIND`、`PUT`、`MOVE`、`GET`、`DELETE`。每份 JSON 有对应 `.ok.json` 提交标记；上传后回读长度与哈希。客户端拒绝带凭据的重定向。
