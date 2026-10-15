package com.littlekitty.xiaokele.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.core.network.embedding.DASHSCOPE_DEFAULT_HOST
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.MiniMaxDirectConfig
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind

@Composable
internal fun MiniMaxSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint("MiniMax M2 与 M2-her 共用此 Key；联网搜索只使用 MiniMax 官方 coding-plan。")

        SettingsSectionTitle("凭据")
        SettingsCard {
            OutlinedTextField(
                value = state.miniMaxKeyInput,
                onValueChange = { state.miniMaxKeyInput = it },
                label = { Text(stringResource(R.string.byok_key_label)) },
                placeholder = { Text(state.miniMaxFingerprint ?: stringResource(R.string.byok_key_placeholder)) },
                visualTransformation = if (state.miniMaxKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { state.miniMaxKeyVisible = !state.miniMaxKeyVisible }) {
                    Text(stringResource(if (state.miniMaxKeyVisible) R.string.byok_hide_key else R.string.byok_show_key))
                }
                TextButton(onClick = state::saveMiniMaxKey) { Text(stringResource(R.string.byok_save_key)) }
                OutlinedButton(onClick = state::deleteMiniMaxKey) { Text(stringResource(R.string.byok_delete_key)) }
            }
            SettingsStatusLine("当前 Key", state.miniMaxFingerprint ?: "未配置")
        }

        SettingsSectionTitle("${stringResource(R.string.byok_region_label)}与地址")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.region == ByokSettingsStore.REGION_CN, onClick = { state.region = ByokSettingsStore.REGION_CN })
                Text(stringResource(R.string.byok_region_cn))
                RadioButton(state.region == ByokSettingsStore.REGION_INTL, onClick = { state.region = ByokSettingsStore.REGION_INTL })
                Text(stringResource(R.string.byok_region_intl))
            }
            OutlinedTextField(
                value = state.endpoint,
                onValueChange = { state.endpoint = it },
                label = { Text("Endpoint（高级，可留空）") },
                placeholder = { Text(stringResource(R.string.byok_endpoint_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsStatusLine("生成模型", "${MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL} / M2-her")
            Button(onClick = state::saveMiniMaxSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.byok_save_settings))
            }
        }

        SettingsSectionTitle("能力验证")
        SettingsCard {
            SettingsStatusLine("M2 + M2-her", if (state.officialModelsValidated) "已验证" else "未验证")
            SettingsHint("测试成功后会绑定当前 Key、区域和地址；任一变化都需要重新测试。")
            Button(
                onClick = state::runMiniMaxTest,
                enabled = !state.miniMaxTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.miniMaxTesting) CircularProgressIndicator(Modifier.height(18.dp))
                else Text(stringResource(R.string.byok_test_key))
            }
        }

        SettingsEntryRow(
            title = "联网搜索",
            summary = if (state.webSearchEnabled) "已启用 · 由 MiniMax coding-plan 执行" else "已关闭 · 不再发起搜索请求",
            onClick = { state.navigate(SettingsPage.WEB_SEARCH) },
        )
    }
}

@Composable
internal fun BailianSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint(
            "阿里百炼的生成 Chat 与记忆 Embedding 共用这一处 Host/API Key：" +
                "Chat 使用 compatible-mode 接口，Embedding 使用官方向量接口。",
        )

        SettingsSectionTitle("共享凭据")
        SettingsCard {
            OutlinedTextField(
                value = state.bailianHost,
                onValueChange = { state.bailianHost = it },
                label = { Text(stringResource(R.string.embedding_api_host_label)) },
                placeholder = { Text(stringResource(R.string.embedding_api_host_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsHint(
                "默认域名对应华北2（北京）地域。新加坡、中国香港等地域的 Key 必须填写对应域名；" +
                    "也可在百炼控制台「业务空间管理」复制专属 API Host。",
            )
            OutlinedTextField(
                value = state.bailianKeyInput,
                onValueChange = { state.bailianKeyInput = it },
                label = { Text(stringResource(R.string.embedding_api_key_label)) },
                placeholder = { Text(state.bailianFingerprint ?: stringResource(R.string.embedding_api_key_placeholder)) },
                visualTransformation = if (state.bailianKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { state.bailianKeyVisible = !state.bailianKeyVisible }) {
                    Text(stringResource(if (state.bailianKeyVisible) R.string.byok_hide_key else R.string.byok_show_key))
                }
                TextButton(onClick = state::saveBailianCredentials) { Text("保存共享凭据") }
                OutlinedButton(onClick = state::deleteBailianKey) { Text(stringResource(R.string.embedding_delete)) }
            }
            Button(
                onClick = state::fetchBailianDeepSeekModels,
                enabled = !state.bailianTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.bailianTesting) CircularProgressIndicator(Modifier.height(18.dp))
                else Text("连接并获取 DeepSeek 模型")
            }
            SettingsStatusLine("当前 Key", state.bailianFingerprint ?: "未配置")
            SettingsStatusLine("有效 Host", state.bailianHost.ifBlank { "默认域名" })
        }

        SettingsSectionTitle("能力配置")
        SettingsEntryRow(
            title = "生成模型",
            summary = "${state.bailianAvailableModels.size} 个 DeepSeek 模型 · " + if (state.bailianModelsValidated) "已验证" else "未验证",
            onClick = { state.navigate(SettingsPage.BAILIAN_MODELS) },
        )
        SettingsEntryRow(
            title = "联网搜索",
            summary = if (state.webSearchEnabled) "已启用 · 由 MiniMax coding-plan 执行" else "已关闭",
            onClick = { state.navigate(SettingsPage.WEB_SEARCH) },
        )
        SettingsEntryRow(
            title = "记忆 Embedding",
            summary = "${state.embeddingModel} · " + if (state.embeddingEnabled) "已启用" else "已关闭",
            onClick = { state.navigate(SettingsPage.EMBEDDING) },
        )
        SettingsHint("删除共享 Key 会同时停用百炼 Chat 与 Embedding；不会借用 MiniMax 凭据。")
    }
}

@Composable
internal fun BailianChatModelPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint("先在上一页连接并获取模型目录；这里只显示当前业务空间可用的 DeepSeek 模型。为 Agent 选好模型后再测试。")
        SettingsSectionTitle("模型与验证")
        SettingsCard {
            SettingsStatusLine("可选 DeepSeek 模型", state.bailianAvailableModels.size.toString())
            SettingsStatusLine("能力验证", if (state.bailianModelsValidated) "已验证" else "未验证")
            SettingsStatusLine("共享 Host", state.bailianHost.ifBlank { DASHSCOPE_DEFAULT_HOST })
            SettingsStatusLine("共享 Key", state.bailianFingerprint ?: "未配置")
            Button(
                onClick = state::runBailianChatTest,
                enabled = !state.bailianTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.bailianTesting) CircularProgressIndicator(Modifier.height(18.dp)) else Text("测试已选模型")
            }
        }
        SettingsEntryRow("共享凭据", "Host/API Key 在「阿里百炼」页维护", onClick = { state.navigate(SettingsPage.BAILIAN) })
        SettingsEntryRow("Agent 路由", agentRouteSummary(state.agentRoutes), onClick = { state.navigate(SettingsPage.AGENT_ROUTES) })
    }
}

@Composable
internal fun WebSearchSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsSectionTitle("开关")
        SettingsCard {
            SettingsSwitchRow(
                title = "允许联网搜索",
                summary = "关闭后本地生活、每日搜索和手动刷新都不再发起网络请求。",
                checked = state.webSearchEnabled,
                onCheckedChange = state::changeWebSearchEnabled,
            )
        }
        SettingsSectionTitle("搜索后端")
        SettingsHint("当前版本只允许 MiniMax 官方 coding-plan 用于联网搜索；百炼凭据不会被用于搜索。")
        SettingsCard {
            SettingsStatusLine("MiniMax Key", state.miniMaxFingerprint ?: "未配置")
            SettingsStatusLine("双模型能力", if (state.officialModelsValidated) "已验证" else "未验证")
        }
        SettingsHint("缺少 MiniMax Key 或能力未验证时，搜索会 fail-closed，不会回退到百炼。")
        Button(onClick = { state.navigate(SettingsPage.MINIMAX) }, modifier = Modifier.fillMaxWidth()) { Text("去配置 MiniMax 凭据") }
    }
}

@Composable
internal fun EmbeddingSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint(stringResource(R.string.embedding_settings_note))
        SettingsSectionTitle(stringResource(R.string.embedding_settings_title))
        SettingsCard {
            SettingsSwitchRow(
                title = "启用记忆向量检索",
                summary = "关闭后记忆检索退回本地词法匹配，不再产生向量请求，也不影响聊天与记忆写入。",
                checked = state.embeddingEnabled,
                onCheckedChange = state::changeEmbeddingEnabled,
            )
        }
        SettingsSectionTitle("向量模型")
        SettingsCard {
            OutlinedTextField(
                value = state.embeddingModel,
                onValueChange = { state.embeddingModel = it },
                label = { Text(stringResource(R.string.embedding_model_label)) },
                placeholder = { Text(EmbeddingSettingsStore.DEFAULT_MODEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsStatusLine("共享 Host", state.bailianHost.ifBlank { DASHSCOPE_DEFAULT_HOST })
            SettingsStatusLine("共享 Key", state.bailianFingerprint ?: "未配置")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = state::saveEmbeddingSettings) { Text(stringResource(R.string.embedding_save)) }
                OutlinedButton(onClick = state::runEmbeddingTest, enabled = !state.embeddingTesting) {
                    if (state.embeddingTesting) CircularProgressIndicator(Modifier.height(18.dp))
                    else Text(stringResource(R.string.embedding_test))
                }
            }
        }
        SettingsHint("Host 与 API Key 和百炼 Chat 共用，只在「阿里百炼」页维护，避免两处填写不一致。")
        SettingsEntryRow("共享凭据", "在「阿里百炼」页维护 Host/API Key", onClick = { state.navigate(SettingsPage.BAILIAN) })
    }
}

@Composable
internal fun AgentRouteSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint("百炼模型来自已连接业务空间的 DeepSeek 目录。路由变化后需要重新测试对应供应商能力。")
        TaskKind.values().forEach { task ->
            val route = state.agentRoutes.getValue(task)
            SettingsCard {
                Text(taskLabel(task), style = MaterialTheme.typography.titleSmall)
                AgentRouteEditor(route, state.bailianAvailableModels) { provider, modelId, complexity ->
                    state.updateAgentRoute(task, provider, modelId, complexity)
                }
            }
        }
        SettingsEntryRow("当前路由总览", agentRouteSummary(state.agentRoutes), onClick = { state.navigate(SettingsPage.HOME) })
    }
}

@Composable
internal fun AgentRouteEditor(
    route: AgentModelRoute,
    bailianModels: List<String>,
    onChange: (ModelProvider, String, RouteComplexity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RouteDropdown("普通供应商", route.provider, ModelProvider.values().toList()) { provider ->
            onChange(provider, modelOptions(provider, bailianModels).first(), RouteComplexity.DEFAULT)
        }
        RouteDropdown("普通模型", route.defaultModelId, modelOptions(route.provider, bailianModels)) { model ->
            onChange(route.provider, model, RouteComplexity.DEFAULT)
        }
        RouteDropdown("复杂模型", route.complexModelId, modelOptions(route.provider, bailianModels)) { model ->
            onChange(route.provider, model, RouteComplexity.COMPLEX)
        }
    }
}

@Composable
private fun <T> RouteDropdown(label: String, value: T, options: List<T>, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = { expanded = false; onSelected(option) },
                )
            }
        }
    }
}

internal fun modelOptions(provider: ModelProvider, bailianModels: List<String> = emptyList()): List<String> = when (provider) {
    ModelProvider.MINIMAX_OFFICIAL -> listOf(
        ByokSettingsStore.DEFAULT_MODEL,
        ByokSettingsStore.MINIMAX_M2_MODEL,
        ByokSettingsStore.OFFICIAL_HER_MODEL,
    )
    ModelProvider.BAILIAN -> bailianModels.ifEmpty {
        listOf(ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL, ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL)
    }
}
