# 狗狗伴伴

一个通用的小狗陪伴 Android 应用。用户自己给小狗起名字、自己决定被怎么称呼，并确认一份属于自己的人格设定；之后可以和它聊天、让它在后台自然地关心你、查看它写下的日记，以及用它记住的片段继续对话。

- **自带 Key（BYOK）**：应用直接调用你自己配置的生成服务，中间没有任何代理服务器。
- **只支持两家服务**：MiniMax 官方 与 阿里百炼 DeepSeek。可以只用一家，也可以两家混用。
- **默认无排班、无位置围栏**：不读取你的工作班次，也不判断你在公司、医院还是家里。
- **首次创建先于一切**：在确认人格之前不会开始聊天、不会写入记忆，也不会启动后台主动任务。

## 功能

| 功能 | 说明 |
|---|---|
| 聊天 | 单会话时间线，消息本地持久化，进程重启后仍可恢复 |
| 首次创建 | 小狗名字、对你的称呼、性别表述或选择不透露、声明年龄、单一服务真实验证、中性人格草稿逐字编辑、二次确认后原子保存 |
| 主动陪伴 | 根据你自己的解锁与聊天节奏推测起床窗口，用无声通知或待投递气泡轻触一次 |
| 本地生活 | 使用前台当前定位搜索附近的活动与美食，结果保留来源链接 |
| 记忆与画像 | 本地记忆、稳定画像与行为倾向；写入前经过独立审查 |
| 日记本 | 从小狗视角写下的短篇，明确标注是创作而不是客观记忆 |
| 悬浮窗与桌面组件 | 可选的角色悬浮窗和桌面状态组件 |

## 生成服务与模型

| 供应商 | 用途 | 模型 |
|---|---|---|
| 阿里百炼 DeepSeek | 接待与计划、决策、审查、记忆、画像综合 | `deepseek-v4-flash` / `deepseek-v4-pro` |
| MiniMax 官方 | 前台角色表达（B2）、本地生活检索 | `M2-her`、`MiniMax-M2` |

每个 Agent 的请求只会携带**它自己供应商**的凭据，不会借用另一家的 Key，也不会在失败时切换到另一家。

## 构建

要求：Android Studio 自带 JDK 17，Android SDK 34。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./scripts/test-with-context.sh
./gradlew :app:assembleDebug
```

产物：

- debug：`app/build/outputs/apk/debug/app-debug.apk`
- release：`app/build/outputs/apk/release/app-release-unsigned.apk`（未签名，需自行签名后才能安装）

## 验证

```bash
# 单元测试（四个模块）
./scripts/test-with-context.sh

# lint 与构建
./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease

# APK 内容审计：秘密、调试类、排班/围栏残留、个性化残留
./scripts/verify-public-release.sh app/build/outputs/apk/release/app-release-unsigned.apk

# 两家供应商真实链路验证（读取本地 secrets.properties，不输出任何 Key）
python3 scripts/verify_public_providers.py
```

## 应用权限

| 权限 | 为什么需要 |
|---|---|
| `INTERNET` | 调用你自己配置的生成服务与天气/检索接口 |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | 前台获取当前城市，用于天气与本地生活搜索 |
| `POST_NOTIFICATIONS` | 主动陪伴的无声通知与未读角标 |
| `SYSTEM_ALERT_WINDOW` | 可选的角色悬浮窗 |
| `FOREGROUND_SERVICE` | 悬浮窗运行时保持前台服务 |

应用**不申请**后台定位权限，也不使用精确闹钟。

## 数据与隐私

- 聊天记录、记忆、画像与人格都存在本机 Room 数据库，不上传到任何你自己的服务器。
- API Key 使用 Android Keystore 加密保存，不使用明文配置。
- 当前版本的设置导出包含明文 Key，请按密码文件保管，不要公开分享。
- 详见 [PRIVACY.md](PRIVACY.md) 与 [SECURITY.md](SECURITY.md)。

## 第三方

模型与素材的第三方说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

**版权所有，保留所有权利（All Rights Reserved）。**

本仓库**不授予**任何复制、修改、再分发、再发布或商业使用的许可。公开发布或提供源码访问不等于授予开源许可。

具体而言，未经版权所有者事先书面许可，不得：

- 复制、fork、镜像或再发布本仓库的全部或部分内容；
- 修改、翻译、演绎或基于本仓库创建衍生作品；
- 将本仓库或其构建产物（APK）用于商业用途或对外分发；
- 移除或修改本仓库中的版权、许可与素材授权声明。

本仓库中使用的外部组件仍受其各自许可约束（见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）；动画素材的授权范围以该文件第 3 节为准，未经确认不得随源码再分发。

如需授权，请联系版权所有者并取得书面同意。
