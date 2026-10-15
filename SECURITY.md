# 安全说明

最后更新：2026-09-22

## 凭据处理

- API Key 只由用户在应用内输入，使用 Android Keystore（AES/GCM/NoPadding，独立别名）加密后保存。
- Key 不写入源码、构建配置、测试夹具、日志或诊断导出。
- 设置导出默认不包含明文 Key。
- 本仓库的 `.gitignore` 覆盖 `**/secrets.properties`、`**/*.jks`、`**/*.keystore` 与 `app/release/`。

## 网络

- 所有生产 HTTP 调用共用 `GovernedHttpTransport`：进程内总并发上限 8、同一服务主机上限 3；收到 429（或带 `Retry-After` 的 503）与连续临时网络失败会开启短暂冷却。治理层拒绝超额工作而不排队。
- 供应商端点必须是不含 user-info 与 fragment 的绝对 HTTPS URL（`HttpsUrlPolicy`）。
- 仅允许应用声明的主机；不自建中间代理。

## 供应商隔离

- 每个 Agent 路由只使用自己供应商的凭据；百炼请求不会携带 MiniMax Key，反之亦然。
- 供应商、模型、Host 或验证版本发生变化会使旧的能力验证失效，未验证的路由 fail-closed。
- `MixedProviderRoutingTest` 断言混用与单服务两种模式下凭据都不跨家。

## 数据边界

- 记忆、画像与人格补丁必须通过独立审查（C_REVIEW）后才写入；本地校验不通过的模型输出一律丢弃。
- 首次创建未确认时，聊天发送、记忆写入与后台任务在代码层被拒绝。
- 诊断导出在写入文件前做敏感词脱敏，且不包含原始向量二进制。

## 发布前检查

```bash
# 解包 APK 并扫描秘密、调试类、排班/围栏残留与个性化残留
./scripts/verify-public-release.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

该脚本会在产物中发现以下任一情况时失败：

- 调试类（`DebugActivity`、`FakeModelService`、`DEBUG_FAKE` 等）；
- 构建期密钥字段（`MINIMAX_CHAT_API_KEY`、`QWEATHER_API_KEY` 等）；
- 疑似密钥形态（`Bearer …`、`sk-…`、`AIza…`、私钥块）；
- 固化坐标或坐标对；
- 排班/围栏残留标识；
- 固定的角色名、用户昵称、专属关系或成人亲密默认。

它允许且仅允许迁移语句中对 `shift_cache` 的 `DROP TABLE` 提及，因为从旧版本升级时必须删除该遗留表。

## 报告问题

本项目暂未附带公开仓库地址与安全联系渠道；正式发布前请在此处补充。请勿在公开渠道贴出 API Key、Authorization 头或完整请求/响应正文。
