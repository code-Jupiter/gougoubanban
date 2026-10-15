package com.littlekitty.xiaokele.discovery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.provider.SourceGroundedSummarizer
import com.littlekitty.xiaokele.provider.SourceSummaryResult
import com.littlekitty.xiaokele.provider.isSafeHttpUrl
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryFeedScreen(
    service: DiscoveryFeedService,
    store: ByokSettingsStore,
    locationResolver: DiscoveryLocationResolver,
    onBack: () -> Unit,
    summarizer: SourceGroundedSummarizer? = null,
) {
    val context = LocalContext.current
    val cache = remember { DiscoveryCacheStore(context) }
    val refreshRateLimiter = remember { DiscoveryRefreshRateLimiter(context) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(cache.load()) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableStateOf(cache.lastSavedAt()) }
    var overview by remember { mutableStateOf<String?>(null) }
    var overviewLoading by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                ),
                title = { Text(stringResource(R.string.discovery_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.news_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            val location = locationResolver.resolve()
                            if (location == null) {
                                error = context.getString(R.string.discovery_location_unavailable)
                                loading = false
                                return@launch
                            }
                            // Reserve before the provider call. Failed / 429
                            // responses intentionally consume the same budget,
                            // otherwise an outage becomes a tap-driven request loop.
                            val retryAfterMillis = refreshRateLimiter.tryAcquire()
                            if (retryAfterMillis != null) {
                                error = context.getString(
                                    R.string.discovery_refresh_rate_limited,
                                    waitMinutes(retryAfterMillis),
                                )
                                loading = false
                                return@launch
                            }
                            when (val result = service.fetchDaily(
                                areas = setOf(location.area),
                                categories = store.selectedCategories().ifEmpty { setOf("events", "food") },
                            )) {
                                is DiscoveryFeedResult.Success -> {
                                    cache.save(result.items, result.generatedAt)
                                    items = result.items
                                    lastUpdated = System.currentTimeMillis()
                                    overview = null
                                }
                                is DiscoveryFeedResult.Empty -> {
                                    cache.save(emptyList(), null)
                                    items = emptyList()
                                    lastUpdated = System.currentTimeMillis()
                                    overview = null
                                }
                                is DiscoveryFeedResult.Failure -> error = result.error.safeUserMessage()
                            }
                            loading = false
                        }
                    },
                    enabled = loading.not(),
                ) { Text(stringResource(R.string.discovery_refresh)) }
                Text(
                    text = if (lastUpdated > 0) {
                        stringResource(R.string.discovery_last_updated, formatEpoch(lastUpdated))
                    } else {
                        stringResource(R.string.discovery_not_synced)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.discovery_default_scope), style = MaterialTheme.typography.bodySmall)
            if (summarizer != null && items.isNotEmpty()) {
                TextButton(
                    enabled = !loading && !overviewLoading,
                    onClick = {
                        scope.launch {
                            overviewLoading = true
                            try {
                                overview = when (val value = summarizer.summarize(items)) {
                                    is SourceSummaryResult.Success -> value.summary
                                    is SourceSummaryResult.Failure -> value.error.safeUserMessage()
                                }
                            } finally {
                                overviewLoading = false
                            }
                        }
                    },
                ) {
                    if (overviewLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.discovery_generate_overview))
                    }
                }
                overview?.let { text ->
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            } else if (items.isEmpty()) {
                Text(stringResource(R.string.discovery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(items = items, key = { it.id }) { item ->
                        DiscoveryCard(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryCard(item: DiscoveryItem) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isSafeHttpUrl(item.sourceUrl)) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                }
            },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("${areaLabel(item.area)} · ${categoryLabel(item.category)}", style = MaterialTheme.typography.labelSmall)
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(item.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("${item.sourceName} · ${item.publishedAt.orEmpty()}", style = MaterialTheme.typography.labelSmall)
            TextButton(
                onClick = {
                    if (isSafeHttpUrl(item.sourceUrl)) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                    }
                },
            ) {
                Text("打开来源网页")
            }
            Text(
                item.sourceUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun areaLabel(area: String): String = when (area) {
    "liandu" -> "莲都区"
    "hangzhou" -> "杭州"
    else -> area
}

private fun categoryLabel(category: String): String = when (category) {
    "events" -> "活动"
    "food" -> "美食"
    else -> category
}

private fun formatEpoch(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"))
        .format(Instant.ofEpochMilli(epochMillis))
}.getOrDefault("")

private fun waitMinutes(millis: Long): Long =
    ((millis + 60_000L - 1L) / 60_000L).coerceAtLeast(1L)
