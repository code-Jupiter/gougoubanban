package com.littlekitty.xiaokele.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationRulesScreen(
    store: AutomationRuleStore,
    onBack: () -> Unit,
) {
    val document = remember(store) { store.load() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                ),
                title = { Text("当前规则") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.news_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("schema ${document.schemaVersion} · ${document.rules.size} 条规则", style = MaterialTheme.typography.bodySmall) }
            if (document.rules.isEmpty()) {
                item { Text("当前没有配置规则。", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(document.rules, key = { it.id }) { rule -> RuleCard(rule) }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: AutomationRule) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = if (rule.enabled) rule.name else "${rule.name}（已停用）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (rule.description.isNotBlank()) Text(rule.description, style = MaterialTheme.typography.bodySmall)
            Text("触发：${rule.trigger.eventType}", style = MaterialTheme.typography.bodySmall)
            rule.trigger.conditions.forEach { condition ->
                Text("条件：${condition.field} ${condition.operator} ${condition.value}", style = MaterialTheme.typography.bodySmall)
            }
            Text("模式：${rule.mode} · 优先级：${rule.priority} · 冷却：${rule.cooldownMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
            if (rule.modelInstruction.isNotBlank()) Text("希望模型做什么：${rule.modelInstruction}", style = MaterialTheme.typography.bodyMedium)
            rule.fixedMessage?.takeIf { it.isNotBlank() }?.let { Text("固定消息：$it", style = MaterialTheme.typography.bodyMedium) }
            Text("可用工具：${rule.allowedTools.ifEmpty { listOf("无") }.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            Text("允许动作：${rule.allowedActions.ifEmpty { listOf("无") }.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
