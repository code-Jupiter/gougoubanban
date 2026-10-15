# LittleKitty Agent Instructions

## 开始任务前

- 先读取 `docs/AI_DEVELOPMENT_CONTEXT.md`。
- 再读取与当前任务直接相关的 `docs/handoffs/` 文档。
- 先运行 `git status --short`，确认用户未提交的修改；不得覆盖、回滚或清理这些修改。
- 不要凭记忆推断项目环境、测试命令、JDK、服务端链路或权限配置。

## 测试、构建和运行前置要求

只要任务涉及测试、编译、构建 APK、lint、验证脚本、启动 App 或真机验收，必须先读取：

- `docs/AI_DEVELOPMENT_CONTEXT.md`
- `docs/TESTING_CONTEXT.md`

测试优先通过 `./scripts/test-with-context.sh` 执行；如果确实需要直接调用 Gradle，也必须先完成上述文档检查，并使用文档规定的 Android Studio JDK 17。

执行前必须确认：

1. 当前仓库和模块范围正确；Android 任务默认只修改 Android 仓库。
2. 没有把 `secrets.properties`、真实 API Key、Authorization、完整敏感请求头或响应写入日志、测试夹具、APK 或 Git。
3. 测试命令与改动范围相称；涉及发布、核心调度或跨模块 DTO 时执行相应的完整门禁。
4. 修改文件前先读取文件，使用小范围补丁；不要用复杂 Python 字符串拼接生成或改写 Kotlin/Compose/测试文件。

如果上下文文档与当前代码或环境不一致，先报告不一致，再决定是否修改文档或代码。

## 完成报告

最终报告必须包含：修改文件、实际执行的命令、测试或构建结果、失败原因及限制、commit（如有）和工作区状态。
