# 开源与配置说明

## 发布边界

公开目录包含客户端、备份服务、通用部署样例、功能/接口文档和单独校验过的安装包。原项目的历史验收文档、真机截图、语音录音、原始基准结果和私有 Nginx 配置不在此快照内。新建仓库时只复制快照内容，不要复制原项目整个目录或 `.git`。

**当前原项目 Git 历史不能直接作为公开历史。** 历史提交曾包含私人服务器域名、测试产物和真机截图；删除工作树文件不会删除旧提交。开源时从快照文件建立新仓库和新初始提交，不推送原有 `.git` 历史或旧 tag。已在外部共享过的凭据应按服务商流程轮换。

## 配置

1. 安装 App 后进入「配置 → 语音与 AI」，填写自己的阿里云 ASR、DeepSeek 主线路凭据和地址；「备用 LLM」非必填。凭据保存在本机加密存储中，不由 Gradle 注入 APK。未配置仍可使用本地库存、搜索、历史和备份功能。
2. 自建备份服务通过 `backup-server/deploy/kuguan-backup.env.example` 设置存储、容量和限流。通过服务端 CLI 生成每设备访问令牌，再在 App 的「配置 → 备份与恢复」填写自有 HTTPS `/api/v1` 地址和令牌。
3. 部署反向代理可参考 `tools/nginx/backup.example.com.conf.example`，自行替换主机名与证书路径。异地归档样例里的主机和 GPG 接收者也必须自行填写。
4. 在本仓库根目录执行 `python tools/check-public-tree.py`，并人工检查本次新增的二进制文件、构建产物与 Git 差异。

发布目录的 APK 是正式签名包。自行构建同包名的正式版，需要在 `config/release-signing.local.properties` 提供 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，并保证签名证书与原安装一致。不要把该配置或密钥库放入公开仓库。不同签名的 APK 无法直接覆盖原应用；保留用户数据时不能通过卸载重装绕过这一限制。

App 不再从 `secrets.properties`、环境变量或 CI 参数读取运行期 ASR/LLM 密钥。升级旧版后须在 App 内重新填写，旧版已经打入 APK 的密钥应按服务商流程轮换。检查公开产物时仍要扫描 APK、诊断导出和新增二进制文件，避免意外带出凭据。

原项目 `app/src/debug/assets/replay/*.wav` 是私有语音回放数据，快照中已排除。公开 Debug 包的回放按钮没有内置音频样本；需要调试时请使用自己的测试录音。

## 验证

Android：`./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain --no-parallel`。Windows PowerShell 使用 `.\gradlew.bat`。

服务端：在安装 `backup-server` 的 `test` 依赖后执行 `python -m pytest backup-server/tests`（将 `backup-server` 加到 `PYTHONPATH`）。
