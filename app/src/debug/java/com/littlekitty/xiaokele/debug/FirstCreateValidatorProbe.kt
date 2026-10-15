package com.littlekitty.xiaokele.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.firstcreate.FirstCreateServiceValidator
import com.littlekitty.xiaokele.firstcreate.SelectedGenerationProvider
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.ui.theme.XiaoKeLeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Keys for one probe run. `toString` is deliberately redacted: a data-class
 * default would print the secrets into any future log or crash report.
 */
data class FirstCreateProbeKeys(
    val minimaxKey: String,
    val bailianKey: String,
    val source: String,
) {
    override fun toString(): String =
        "FirstCreateProbeKeys(source=$source, minimax=${presence(minimaxKey)}, bailian=${presence(bailianKey)})"

    private companion object {
        fun presence(value: String): String = if (value.isBlank()) "absent" else "present"
    }
}

/**
 * Debug-only probe for the real first-create capability check.
 *
 * It calls the production [FirstCreateServiceValidator.validate] path for both
 * supported providers with real credentials and reports only non-sensitive
 * outcomes: provider name, pass/fail, validated flag, model id, endpoint host
 * and the validator's failure reason text. API keys, `Authorization` headers
 * and full provider responses are never logged, stored in the report or shown.
 *
 * Two invocation paths exist:
 *  - `adb shell am broadcast -a <ACTION>` (optionally with key extras);
 *  - [FirstCreateValidatorProbeActivity], which runs from the pushed key file
 *    and renders the last stored report.
 *
 * Key input, in priority order:
 *  1. intent extras [EXTRA_MINIMAX_KEY] / [EXTRA_BAILIAN_KEY];
 *  2. the app-private file `files/first_create_probe_keys.properties`, pushed
 *     with `adb shell run-as <pkg> sh -c 'cat > files/<name>'`.
 * The file is deleted as soon as it is read; extras are the least private
 * option because an Intent extra can surface in `dumpsys`/task-debug output.
 */
object FirstCreateValidatorProbe {

    const val TAG = "FirstCreateValidatorProbe"
    const val ACTION = "com.littlekitty.xiaokele.debug.FIRST_CREATE_VALIDATE"
    const val EXTRA_MINIMAX_KEY = "minimax_key"
    const val EXTRA_BAILIAN_KEY = "bailian_key"
    const val KEY_FILE_NAME = "first_create_probe_keys.properties"

    private const val PREFS_NAME = "little_kitty_first_create_validator_probe"
    private const val KEY_REPORT = "last_report"
    private const val KEY_REPORTED_AT = "last_reported_at"

    fun latestReport(context: Context): String =
        prefs(context).getString(KEY_REPORT, "").orEmpty()

    fun latestReportedAt(context: Context): String =
        prefs(context).getString(KEY_REPORTED_AT, "").orEmpty()

    fun keyFileHint(context: Context): String = File(context.filesDir, KEY_FILE_NAME).absolutePath

    fun openProbeScreen(context: Context) {
        context.startActivity(
            Intent(context, FirstCreateValidatorProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun resolveKeys(context: Context, intent: Intent?): FirstCreateProbeKeys? {
        val fromIntent = intent?.let { incoming ->
            val minimax = incoming.getStringExtra(EXTRA_MINIMAX_KEY).orEmpty()
            val bailian = incoming.getStringExtra(EXTRA_BAILIAN_KEY).orEmpty()
            if (minimax.isNotBlank() || bailian.isNotBlank()) {
                FirstCreateProbeKeys(minimax, bailian, "intent_extras")
            } else {
                null
            }
        }
        return fromIntent ?: consumeKeyFile(context)
    }

    /** Reads the pushed key file and deletes it immediately, whatever the outcome. */
    fun consumeKeyFile(context: Context): FirstCreateProbeKeys? {
        val candidates = listOf(
            "private_file" to File(context.filesDir, KEY_FILE_NAME),
            "external_file" to File(context.getExternalFilesDir(null), KEY_FILE_NAME),
        )
        for ((source, file) in candidates) {
            if (!file.isFile) continue
            val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            file.delete()
            val parsed = raw?.let { parseKeyFile(it) }
            if (parsed != null) return parsed.copy(source = source)
        }
        return null
    }

    /** Parses `NAME=value` lines; unknown names are ignored. */
    fun parseKeyFile(raw: String): FirstCreateProbeKeys? {
        val values = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                line.substringBefore('=').trim().uppercase() to
                    line.substringAfter('=').trim().trim('"')
            }
        val minimax = values[KEY_NAME_MINIMAX].orEmpty()
        val bailian = values[KEY_NAME_BAILIAN].orEmpty()
        if (minimax.isBlank() && bailian.isBlank()) return null
        return FirstCreateProbeKeys(minimax, bailian, "file")
    }

    /** Runs both provider validations and returns the non-sensitive report. */
    suspend fun run(context: Context, keys: FirstCreateProbeKeys): String {
        val validator = GlobalContext.get().get<FirstCreateServiceValidator>()
        val secrets = listOf(keys.minimaxKey, keys.bailianKey)
        val body = buildString {
            append("inputs minimax=${presence(keys.minimaxKey)} bailian=${presence(keys.bailianKey)}")
            append(" source=").append(keys.source)
            listOf(
                SelectedGenerationProvider.MINIMAX_OFFICIAL to keys.minimaxKey,
                SelectedGenerationProvider.BAILIAN to keys.bailianKey,
            ).forEach { (provider, key) ->
                append('\n').append(validateOne(validator, provider, key, secrets))
            }
        }
        return record(context, body)
    }

    /** Stores a report describing that no credential input was available. */
    fun recordMissingKeys(context: Context, detail: String): String =
        record(context, "RESULT outcome=NO_KEYS reason=$detail")

    /**
     * Non-sensitive app-side corroboration for the human reading the screen:
     * only presence booleans, the region and the durable "Bailian validated
     * against the currently stored key/endpoint" marker. No key material,
     * fingerprint or provider response is exposed.
     */
    fun appStateSummary(context: Context): String = runCatching {
        val byok = GlobalContext.get().get<ByokSettingsStore>()
        val minimaxReady = !byok.loadKeyOrNull().isNullOrBlank()
        val bailianReady = !byok.bailianKeyOrNull().isNullOrBlank()
        "app_state minimax_key_saved=$minimaxReady bailian_key_saved=$bailianReady" +
            " region=${byok.region()} bailian_validated_for_current_key=${byok.bailianModelsValidated()}"
    }.getOrElse { "app_state unavailable" }

    private suspend fun validateOne(
        validator: FirstCreateServiceValidator,
        provider: SelectedGenerationProvider,
        key: String,
        secrets: List<String>,
    ): String {
        if (key.isBlank()) {
            return "RESULT provider=${provider.name} outcome=SKIPPED reason=no key supplied"
        }
        return try {
            when (val outcome = validator.validate(provider, key)) {
                is FirstCreateServiceValidator.Result.Success -> {
                    val state = outcome.validation
                    "RESULT provider=${provider.name} outcome=PASS validated=${state.validated}" +
                        " model=${state.modelId} host=${state.endpointHost}"
                }

                is FirstCreateServiceValidator.Result.Failure ->
                    "RESULT provider=${provider.name} outcome=FAIL reason=${redact(outcome.reason, secrets)}"
            }
        } catch (t: Throwable) {
            val detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            "RESULT provider=${provider.name} outcome=FAIL reason=${redact(detail, secrets)}"
        }
    }

    /**
     * Defensive scrub: even a provider failure message is passed through key
     * redaction and truncated to one log-friendly line.
     */
    private fun redact(text: String, secrets: List<String>): String {
        var safe = text.replace('\n', ' ').replace('\r', ' ').trim()
        secrets.filter { it.isNotBlank() }.forEach { secret -> safe = safe.replace(secret, "[redacted]") }
        return safe.take(300).ifBlank { "unspecified" }
    }

    private fun presence(value: String): String = if (value.isBlank()) "absent" else "present"

    private fun record(context: Context, body: String): String {
        val appContext = context.applicationContext
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val report = "$TAG $stamp\n$body"
        report.lineSequence().forEach { line -> Log.i(TAG, line) }
        prefs(appContext).edit()
            .putString(KEY_REPORT, report)
            .putString(KEY_REPORTED_AT, stamp)
            .commit()
        return report
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_NAME_MINIMAX = "MINIMAX_API_KEY"
    private const val KEY_NAME_BAILIAN = "BAILIAN_API_KEY"
}

/**
 * Debug-only acceptance entry: `adb shell am broadcast -a
 * com.littlekitty.xiaokele.debug.FIRST_CREATE_VALIDATE` runs the real
 * first-create validator for MiniMax official and Bailian. Never compiled into
 * release.
 */
class FirstCreateValidatorProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FirstCreateValidatorProbe.ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val keys = FirstCreateValidatorProbe.resolveKeys(context, intent)
                if (keys == null) {
                    FirstCreateValidatorProbe.recordMissingKeys(
                        context,
                        "no key extras and no ${FirstCreateValidatorProbe.KEY_FILE_NAME}",
                    )
                } else {
                    FirstCreateValidatorProbe.run(context, keys)
                }
            } catch (t: Throwable) {
                // Never log the throwable message: caller input may appear in it.
                Log.e(FirstCreateValidatorProbe.TAG, "probe crashed: ${t.javaClass.name}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Debug-only screen that renders the last stored non-sensitive probe report. */
class FirstCreateValidatorProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoKeLeTheme {
                FirstCreateValidatorProbeScreen()
            }
        }
    }
}

@Composable
private fun FirstCreateValidatorProbeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(FirstCreateValidatorProbe.latestReport(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var appState by remember { mutableStateOf("app_state 读取中…") }

    LaunchedEffect(Unit) {
        appState = withContext(Dispatchers.IO) { FirstCreateValidatorProbe.appStateSummary(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("首次创建服务校验探针（debug）", style = MaterialTheme.typography.headlineSmall)
        Text(
            "对 MiniMax 官方与阿里百炼各调用一次真实 FirstCreateServiceValidator.validate。" +
                "只展示非敏感结果：provider、通过与否、validated、模型、endpoint host、失败原因。" +
                "不会展示或记录 Key、Authorization、供应商原始响应。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                status = "校验中…（一次真实网络调用，可能需要数秒）"
                scope.launch {
                    val keys = withContext(Dispatchers.IO) { FirstCreateValidatorProbe.consumeKeyFile(context) }
                    if (keys == null) {
                        status = "未找到密钥文件：${FirstCreateValidatorProbe.keyFileHint(context)}（读取后会立即删除）"
                    } else {
                        report = FirstCreateValidatorProbe.run(context, keys)
                        status = "已完成；结果同时写入 logcat，tag=${FirstCreateValidatorProbe.TAG}"
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("运行校验（真实接口）") }
        OutlinedButton(
            enabled = !busy,
            onClick = { report = FirstCreateValidatorProbe.latestReport(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("读取最新结果") }
        OutlinedButton(
            enabled = !busy,
            onClick = { context.startActivity(Intent(context, DebugActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开 Debug 中心（概览含同一结果）") }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "上次运行：${FirstCreateValidatorProbe.latestReportedAt(context).ifBlank { "—" }}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            appState,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            report.ifBlank { "尚未运行。可推送密钥文件后点击上方按钮，或使用 adb 广播 ${FirstCreateValidatorProbe.ACTION}。" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
