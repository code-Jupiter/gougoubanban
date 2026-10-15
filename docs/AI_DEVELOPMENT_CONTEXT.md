# 狗狗伴伴 开发上下文

> 本文件是后续 AI 与新贡献者开始任何改动前的必读清单。
> 最后核对：2026-09-22

## 1. 项目与工作区

- 这是一个独立的通用 Android 产品 **狗狗伴伴**，不是任何个人定制版本。
- 应用显示名：`狗狗伴伴`；`applicationId`：`com.dogcompanion.app`；`namespace` 仍为 `com.littlekitty.xiaokele`（内部包名，历史原因，与对外身份无关）。
- 模块：`:app`、`:core:common`、`:core:database`、`:core:network`。
- 开始前先运行 `git status --short`，确认并保护用户未提交的改动。

## 2. 不可违反的架构约束

1. **只用两家生成服务**：MiniMax 官方 与 阿里百炼 DeepSeek。没有 OpenRouter，没有第三方回退，没有跨供应商借 Key。
2. **路由既能独立也能混用**：
   - 混用（默认，与原产品一致）：`A_INTAKE`/`B1_DECISION`/`C_REVIEW`/`D_MEMORY` → 百炼 `deepseek-v4-flash`，`B2_EXPRESSION` → MiniMax `M2-her`；
   - 独立：只配一家时，A/B1/B2/C/D 全部走该家。
   - 每个 Agent 的请求只能带**自己所属供应商**的凭据。`MixedProviderRoutingTest` 是这条契约的可执行证明。
3. **Key 只存 Android Keystore**，不得打印、回显、提交或写进任何仓库文件；`secrets.properties` 已被 `.gitignore` 覆盖。
4. **没有排班、班次、家庭/医院/工作地点围栏**。`PublicEditionNoScheduleTest` 与 `verify-public-release.sh` 是这条约束的门禁。
5. **没有个性化配置**。不得出现固定的角色名、固定的用户昵称、私人外貌或关系设定、成人或亲密默认。身份一律来自用户首次创建的输入，经由 `CompanionPromptIdentity` 注入提示词。`PublicEditionNoPersonalizationTest` 是这条约束的门禁。
6. **首次创建门禁**：未确认人格之前，聊天发送、记忆写入与后台任务在**代码层**被拒绝（`FirstCreateGate`），不是隐藏 UI。

## 3. 首次创建流程

包 `com.littlekitty.xiaokele.firstcreate`：

- `FirstCreateContract.kt`：阶段、字段、纯校验规则（名字/称呼/年龄/性别表述/服务验证/人格安全）。
- `NeutralPersonaTemplate.kt`：中性人格模板，只填充用户输入。
- `FirstCreateStore.kt`：草稿持久化（`app_metadata`）与**单事务**确认；`FirstCreateGate` 是运行时门禁。
- `FirstCreateServiceValidator.kt`：对选定的一家做真实能力验证，失败保留输入且**不回退另一家**。
- `NeutralPersonaTemplate` 写出的 `character_core` 是运行时身份的唯一来源。

年龄只作为“用户声明值 + 填写时间”记录，所有年龄一视同仁，不用年龄或性别推断职业、亲密意愿或心理状态。

## 4. 数据层

- Room 当前版本 `AppDatabase.VERSION = 12`；`MIGRATION_11_12` 会 `DROP TABLE shift_cache`，使历史排班数据在公开版运行时不残留。
- 外部缓存表 `external_cache` 同时服务天气、定位与本地生活，**不是**排班缓存。
- 迁移注册表在 `core/database` 的 `AppMigrations`；改动实体必须同时补迁移与 schema 版本，并更新 `core/database/schemas/`。

## 5. 构建与验收

测试与构建命令见 `docs/TESTING_CONTEXT.md`。发布门禁：

```bash
./scripts/test-with-context.sh
./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
./scripts/verify-public-release.sh app/build/outputs/apk/release/app-release-unsigned.apk
python3 scripts/verify_public_providers.py
```

`verify-public-release.sh` 会解包 APK 并扫描秘密、调试类、排班/围栏残留与个性化残留；它允许且仅允许迁移语句中对 `shift_cache` 的 `DROP TABLE` 提及。

## 6. 修改须知

- 先读文件再改，使用小范围补丁；不要用脚本批量生成 Kotlin/Compose/测试文件。
- 修改 `core/common` 的 DTO 后必须强制重新编译依赖模块。
- 新增能力时优先复用既有实现（`GovernedHttpTransport`、`PolicyResolver`、`TaskModelGateway`、`FirstCreateGate`），不要引入平行机制。
- 公开版没有“隐藏 UI 即等于删除”的做法：删除功能必须同时清理生产路径、DI、Worker、数据层、测试与文档。
