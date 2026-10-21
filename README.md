# 收纳助手

收纳助手是一款 Android 家庭物品管理应用。物品、存放位置、数量和操作历史保存在手机本地；位置名称由使用者自由填写。应用支持语音记账与查询、手动搜索和编辑、提醒，以及本地或自选云端备份。

- [功能介绍](doc/FEATURES.md)
- [客户端与服务端接口规范](doc/API.md)
- [开源与配置说明](doc/OPEN_SOURCE.md)
- [安装包与校验值](release/RELEASE.md)

## 安装与配置

Android 13 及以上可安装 `release/kuguan-0.1.0-20261021.apk`。这是从仓库对应源码构建的正式签名包；发布者和校验值见安装包说明。已有安装若签名不同，Android 不允许直接覆盖安装；请勿为了安装新包清除原应用数据。

本地库存、搜索、历史和本地备份无需云服务。语音识别需要联网：安装后进入「配置 → 语音与 AI」，填写自己的阿里云 ASR Key、DeepSeek 主线路地址/模型/Key，保存后可分别测试连接。备用 LLM 非必填，仅在主线路失败时使用。凭据保存在手机的加密配置里，构建 APK 不注入使用者的 API Key。语音会发送到配置的 ASR 服务，识别文稿和相关库存上下文会发送到配置的 LLM 服务。

云备份是可选功能，可在「配置 → 备份与恢复」设置坚果云 WebDAV 或自建备份服务。备份文件是明文 JSON，服务提供者可读取其内容；本地库存备份不包含语音录音和诊断记录。

## 构建

需要 JDK 17、Android SDK API 36 与 SDK Build Tools。在 Windows PowerShell 中运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-parallel
```

macOS/Linux 用 `./gradlew` 替换 `.\gradlew.bat`。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `com.kuguan.app.debug`。正式签名需要自行在 `config/release-signing.local.properties` 提供私有签名材料，详情见[开源与配置说明](doc/OPEN_SOURCE.md)。可选备份服务位于 `backup-server/`。

自行发布前可运行 `python tools/check-public-tree.py`，并人工检查新增的二进制文件与凭据。本项目自有代码和文档采用 [The Unlicense](UNLICENSE)，允许自由使用、修改和商用，无需署名；第三方依赖仍遵循各自许可证。
