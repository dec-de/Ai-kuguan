# 收纳助手安装包

## 最新版：0.1.1（2026-11-02）

文件：[kuguan-0.1.1-20261102.apk](kuguan-0.1.1-20261102.apk)

| 项目 | 值 |
| --- | --- |
| 版本 | 0.1.1 (`versionCode` 2) |
| 包名 | `com.kuguan.app` |
| 最低 Android 版本 | Android 13（API 33） |
| 签名证书 | `CN=Kuguan Release, O=Kuguan, C=CN` |
| 签名证书 SHA-256 | `D3800D7F913C022633826AE9B71CB3075BE79943CB30AC30603A7B9EFAF11C79` |
| APK SHA-256 | `518996FB9135B86D1EDB4FC4E6C643968B147C1391AC024D677400161D25AF91` |

本版从当前源码构建，更新了物品详情按位置取出、LLM 超时与关闭思考参数，以及可选 OpenRouter 供应商设置。公开源码的 369 项 Android JVM 测试、Debug/Release 构建和备份服务 28 项测试均通过；详情见[更新记录](../update-history.md)。APK 不包含发布者的 ASR/LLM API Key，安装后需在「配置 → 语音与 AI」填写个人凭据。

## 旧版：0.1.0（2026-10-21）

[kuguan-0.1.0-20261021.apk](kuguan-0.1.0-20261021.apk)，SHA-256：`AB8AA52F81B9F4BD4DA18654A66E2E8CC2013051ADF3A3B1B089E3A19AB7D374`。

两版使用同一签名证书，Android 可在保留应用数据的前提下从旧版更新到新版。其他签名证书的同包名 APK 无法直接覆盖安装；请先核对现有应用签名并保护设备数据。
