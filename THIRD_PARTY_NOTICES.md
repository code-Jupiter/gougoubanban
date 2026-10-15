# 第三方声明

最后更新：2026-09-22

本应用包含以下第三方组件与素材。若你是再分发者，请一并保留本文件。

## 1. 运行时依赖

| 组件 | 版本 | 许可 |
|---|---|---|
| Kotlin 标准库 / kotlinx-coroutines | 1.9.22 / 1.7.3 | Apache-2.0 |
| kotlinx-serialization-json | 1.6.2 | Apache-2.0 |
| AndroidX Core KTX (`androidx.core:core-ktx`) | 1.12.0 | Apache-2.0 |
| AndroidX Activity Compose | 1.8.2 | Apache-2.0 |
| AndroidX Lifecycle Runtime Compose | 2.7.0 | Apache-2.0 |
| Jetpack Compose（BOM 2024.02.01：ui、material3、tooling） | 2024.02.01 | Apache-2.0 |
| AndroidX Room（runtime / ktx） | 2.6.1 | Apache-2.0 |
| AndroidX WorkManager (`work-runtime-ktx`) | 2.9.0 | Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0 |
| Koin（`koin-android`、`koin-androidx-compose`） | 3.5.3 | Apache-2.0 |

## 2. 仅构建/测试使用（不进入发布 APK）

| 组件 | 版本 | 许可 |
|---|---|---|
| Android Gradle Plugin | 8.1.2 | Apache-2.0 |
| KSP (Kotlin Symbol Processing) | 1.9.22-1.0.17 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Robolectric | 4.11.1 | MIT |
| AndroidX Test Ext JUnit | 1.1.5 | Apache-2.0 |
| AndroidX WorkManager `work-testing` | 2.9.0 | Apache-2.0 |
| OkHttp MockWebServer | 4.12.0 | Apache-2.0 |

## 3. 图像与动画素材

`app/src/main/res/drawable-nodpi/` 下的 8 个动画（`dog_default_idle`、`dog_waiting`、`dog_sleeping`、`dog_curled_up`、`dog_expecting`、`dog_caring`、`dog_work_guard`、`dog_welcome_back`）与 `app/src/main/assets/animation_manifest.json`：

- 格式：Android Animated WebP，画布 360×843，含 alpha。
- 来源标记：manifest 中记为 `supplied-authorized-animation-v1`，即由项目方提供并获授权用于本应用。
- 这些素材**不是**从任何公开素材库抓取，也不来自第三方产品的私人素材路径。
- 再分发前请确认你持有相应授权；如无授权，请替换为你自己的素材并同步更新 manifest 中的 `source` 字段。

## 4. 外部服务（非本仓库代码）

本应用只调用用户自行配置的外部服务，不打包其 SDK：

| 服务 | 用途 | 说明 |
|---|---|---|
| MiniMax 官方 API | 角色表达、本地生活检索 | 由用户自带 Key，遵循其服务条款 |
| 阿里百炼 DashScope（DeepSeek、Embedding） | 决策、审查、记忆、画像、向量检索 | 由用户自带 Key，遵循其服务条款 |
| 和风天气（可选） | 天气、空气质量与预警 | 用户可自行配置 Host 与 Key |

各服务的模型输出与使用条款由对应服务方决定，与本应用无关。
