package com.littlekitty.xiaokele.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.R

@Composable
internal fun QWeatherSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint(stringResource(R.string.qweather_settings_note))
        SettingsSectionTitle("${stringResource(R.string.qweather_settings_title)} 凭据")
        SettingsCard {
            OutlinedTextField(
                value = state.qWeatherHost,
                onValueChange = { state.qWeatherHost = it },
                label = { Text(stringResource(R.string.qweather_api_host_label)) },
                placeholder = { Text(stringResource(R.string.qweather_api_host_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.qWeatherKeyInput,
                onValueChange = { state.qWeatherKeyInput = it },
                label = { Text(stringResource(R.string.qweather_api_key_label)) },
                placeholder = { Text(state.qWeatherFingerprint ?: stringResource(R.string.qweather_api_key_placeholder)) },
                visualTransformation = if (state.qWeatherKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { state.qWeatherKeyVisible = !state.qWeatherKeyVisible }) {
                    Text(stringResource(if (state.qWeatherKeyVisible) R.string.byok_hide_key else R.string.byok_show_key))
                }
                TextButton(onClick = state::saveQWeather) { Text(stringResource(R.string.qweather_save)) }
                OutlinedButton(onClick = state::deleteQWeatherKey) { Text(stringResource(R.string.qweather_delete)) }
            }
            SettingsStatusLine("当前 Key", state.qWeatherFingerprint ?: "未配置")
        }

        SettingsSectionTitle("连接测试")
        SettingsCard {
            Button(
                onClick = state::runQWeatherTest,
                enabled = !state.qWeatherTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.qWeatherTesting) CircularProgressIndicator(Modifier.height(18.dp))
                else Text(stringResource(R.string.qweather_test))
            }
        }
    }
}

@Composable
internal fun ConfigTransferSettingsPage(
    state: SettingsScreenState,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    SettingsPageColumn {
        SettingsHint("导出当前配置会包含 API Key；导入会覆盖对应配置，但不会恢复系统权限。")
        SettingsSectionTitle("配置文件")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onImport,
                enabled = !state.configTransferBusy,
                modifier = Modifier.weight(1f),
            ) { Text("导入配置") }
            Button(
                onClick = { state.showConfigExportWarning = true },
                enabled = !state.configTransferBusy,
                modifier = Modifier.weight(1f),
            ) { Text("导出配置") }
        }
        SettingsHint("导入后，悬浮窗与通知等系统权限仍需在系统设置中单独授予。")
    }

    if (state.showConfigExportWarning) {
        AlertDialog(
            onDismissRequest = { if (!state.configTransferBusy) state.showConfigExportWarning = false },
            title = { Text("导出配置前确认") },
            text = {
                Text(
                    "导出文件会包含 MiniMax、阿里百炼、天气和 Embedding API Key 的明文内容。" +
                        "文件只会保存到你选择的位置，请勿发送给他人或上传到公共网盘。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.configTransferBusy,
                    onClick = { state.showConfigExportWarning = false; onExport() },
                ) { Text("继续导出") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.configTransferBusy,
                    onClick = { state.showConfigExportWarning = false },
                ) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun DiagnosticsSettingsPage(state: SettingsScreenState) {
    SettingsPageColumn {
        SettingsHint(stringResource(R.string.diagnostics_export_note))
        SettingsSectionTitle(stringResource(R.string.diagnostics_export_title))
        Button(
            onClick = { state.showDiagnosticsPrivacyDialog = true },
            enabled = !state.diagnosticsExporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.diagnosticsExporting) CircularProgressIndicator(Modifier.height(18.dp))
            else Text(stringResource(R.string.diagnostics_export_button))
        }
        if (state.diagnosticsExporting) SettingsHint("正在整理并脱敏聊天、AI 和后台任务日志…")
    }

    if (state.showDiagnosticsPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.diagnosticsExporting) state.showDiagnosticsPrivacyDialog = false },
            title = { Text(stringResource(R.string.diagnostics_sensitive_words_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.diagnostics_sensitive_words_note))
                    OutlinedTextField(
                        value = state.sensitiveWordInput,
                        onValueChange = { state.sensitiveWordInput = it },
                        placeholder = { Text(stringResource(R.string.diagnostics_sensitive_words_placeholder)) },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.diagnosticsExporting,
                    onClick = {
                        state.showDiagnosticsPrivacyDialog = false
                        state.runDiagnosticsExport(state.sensitiveWordInput)
                    },
                ) { Text(stringResource(R.string.diagnostics_sensitive_words_confirm)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.diagnosticsExporting,
                    onClick = { state.showDiagnosticsPrivacyDialog = false },
                ) { Text(stringResource(R.string.diagnostics_sensitive_words_cancel)) }
            },
        )
    }
}
