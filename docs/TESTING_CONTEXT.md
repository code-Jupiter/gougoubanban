# 狗狗伴伴 测试环境上下文

本文件是测试、构建、lint、APK 审计与真机验收前的必读清单。完整背景见 `docs/AI_DEVELOPMENT_CONTEXT.md`。

## 环境

- 使用 Android Studio 自带 JDK 17：

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```

- 测试或构建前先运行 `git status --short`，确认没有把用户未提交的改动当作自己的改动。
- `secrets.properties` 已被 `.gitignore` 覆盖，用于本地真实 API 验证；**绝不允许**提交、打印或写进测试夹具。

## 常用验证命令

默认单元测试（四个模块）：

```bash
./scripts/test-with-context.sh
```

等价的直接调用：

```bash
./gradlew :core:common:testDebugUnitTest \
  :core:network:testDebugUnitTest \
  :core:database:testDebugUnitTest \
  :app:testDebugUnitTest --console=plain
```

发布门禁：

```bash
./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --console=plain

# APK 内容审计（秘密、调试类、排班/围栏残留、个性化残留）
./scripts/verify-public-release.sh app/build/outputs/apk/release/app-release-unsigned.apk

# 两家供应商的真实链路验证（只读取 secrets.properties，不输出任何 Key）
python3 scripts/verify_public_providers.py
```

## 门禁测试（改动相关功能时必须通过）

- `PublicEditionNoScheduleTest`：公开版不存在排班/班次/固定地点围栏，`CurrentContext` 无 shift 字段，`AppDatabase` 无 shift DAO，schema 为 12，manifest 无后台定位。
- `PublicEditionNoPersonalizationTest`：生产源码不含固定角色名、固定用户昵称、私人外貌/关系设定或成人亲密默认；身份只来自已确认的 `ContextPack.character`。
- `MixedProviderRoutingTest`：混用与单服务两种路由下，每个 Agent 的请求只携带自己供应商的凭据。
- `FirstCreateRulesTest` / `FirstCreateStoreTest`：首次创建校验规则、草稿可恢复、确认是单事务、门禁默认关闭。

## 测试陷阱

- `UNKNOWN` 必须保持未知，AI 不能猜测。
- MiniMax 是 App 直接调用用户配置的服务；不要恢复聊天代理路径。
- 修改 `core/common` DTO 后必须先重新编译依赖模块。
- Kotlin、Compose 与测试文件先读取再用小补丁修改，不要用脚本批量生成。
- 真实 API 验收不得输出 Key、Authorization、完整请求或完整响应。
- MiniMax-M2 会先生成 `reasoning_content`；给出过小的 token 上限会导致 `content` 为空，验收时不要把它误判为链路失败。

## 真机 / 模拟器验收

- WorkManager 不需要“允许后台运行”弹窗，也不是精确闹钟。
- Android 13+ 通知权限、Android 12+ 前台定位权限需要在目标设备上人工确认。
- 首次创建未完成时，后台任务与悬浮窗都**不应**启动；这是门禁的一部分，验收时要实际检查 logcat。
- 电池优化白名单、厂商自启动与后台冻结无法由通用 API 保证，需在目标设备上人工确认。

## 报告要求

报告必须列出实际执行的命令、结果、失败或限制、修改的文件与 `git status --short` 状态；不得只报告“成功”而没有验证依据。
