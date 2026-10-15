package com.littlekitty.xiaokele.firstcreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * First-run experience.
 *
 * The user lands on a screen whose only action is to test a model connection.
 * Nothing else is reachable until at least one provider has a verified
 * credential, because every later step (and every chat reply) depends on that.
 * After that the user describes what they want, picks a verified model to write
 * the persona, edits it, and confirms.
 *
 * Confirmation locks the persona. The user is told this before they confirm, and
 * the app offers no way to rebuild it afterwards.
 */
@Composable
fun FirstCreateScreen(
    store: FirstCreateStore,
    validator: FirstCreateServiceValidator,
    readinessReader: ConnectionReadinessReader,
    personaGenerator: PersonaGenerator,
    onOpenSettings: () -> Unit,
    onCompleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<FirstCreateDraft?>(null) }
    var connections by remember { mutableStateOf<ConnectionSnapshot?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ageInput by remember { mutableStateOf("") }
    // Text edits arrive much faster than a Room write can finish.  Keep the UI
    // state authoritative, and serialize persistence so an older write cannot
    // put an earlier (or empty) draft back into the form.
    val draftSaveMutex = remember { Mutex() }
    var draftRevision by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val loaded = store.loadDraft()
        ageInput = loaded.declaredAge?.toString().orEmpty()
        draft = loaded
        connections = readinessReader.read()
    }

    // Re-read the connection state whenever this screen comes back to the
    // foreground. Without it, a user who configured and verified a key in
    // Settings returns to a stale "还没通过验证" gate and reasonably concludes
    // the verification failed.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            connections = readinessReader.read()
        }
    }

    val current = draft
    val snapshot = connections
    if (current == null || snapshot == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    fun update(next: FirstCreateDraft) {
        draft = next
        val revision = draftRevision + 1
        draftRevision = revision
        scope.launch {
            draftSaveMutex.withLock {
                // A newer keystroke or stage transition already owns the
                // persisted draft.  Do not let this stale snapshot overwrite
                // it after a suspended Room operation resumes.
                if (revision != draftRevision) return@withLock
                store.saveDraft(next)
            }
        }
    }

    /**
     * Applies a change to the newest draft, not to the value captured by the
     * composition that rendered a particular input field.  A screen may render
     * several editable fields at once, so using `current.copy(...)` in their
     * callbacks can otherwise discard a value entered in a sibling field.
     */
    fun updateLatest(transform: (FirstCreateDraft) -> FirstCreateDraft) {
        val latest = draft ?: return
        update(transform(latest))
    }

    /** Advance one stage after validating the current one. */
    fun advance() {
        // Read the state at click time rather than the composition snapshot
        // that rendered the button.  This is important when a user types a
        // name and immediately taps “下一步”.
        val latest = draft ?: return
        val (next, validation) = FirstCreateRules.advance(latest)
        if (validation is FirstCreateValidation.Invalid) {
            status = validation.reason
        } else {
            status = null
            notice = null
            update(next)
        }
    }

    fun back() {
        val latest = draft ?: return
        latest.stage.previous?.let {
            status = null
            notice = null
            update(latest.copy(stage = it))
        }
    }

    /** The brief, assembled once so generation and rendering cannot disagree. */
    fun briefOf(source: FirstCreateDraft) = PersonaInput(
        petName = source.petName.trim(),
        userAddress = source.userAddress.trim(),
        relationship = source.relationship.trim(),
        desiredPersonality = source.desiredPersonality.trim(),
        desiredTowardUser = source.desiredTowardUser.trim(),
        desiredStyle = source.desiredStyle.trim(),
        avoid = source.avoid.trim(),
    )

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (current.stage == FirstCreateStage.CONNECTION_GATE) "狗狗伴伴" else "创建你的小狗",
                style = MaterialTheme.typography.headlineSmall,
            )

            if (current.stage != FirstCreateStage.CONNECTION_GATE) {
                Text(
                    "第 ${current.stage.stepNumber}/5 步 · ${stageLabel(current.stage)}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            when (current.stage) {
                FirstCreateStage.CONNECTION_GATE -> ConnectionGateContent(
                    snapshot = snapshot,
                    busy = busy,
                    onRecheck = { scope.launch { connections = readinessReader.read() } },
                    onOpenSettings = onOpenSettings,
                    onStart = {
                        if (!snapshot.hasUsableProvider) {
                            status = "请先配置并验证至少一家生成服务。"
                        } else {
                            status = null
                            updateLatest { it.copy(stage = FirstCreateStage.PET_NAME) }
                        }
                    },
                )

                FirstCreateStage.PET_NAME -> {
                    Text("先给它起个名字。这个名字会用在聊天、日记和所有它会说话的地方。")
                    OutlinedTextField(
                        value = current.petName,
                        onValueChange = { value ->
                            updateLatest { it.copy(petName = value) }
                        },
                        label = { Text("小狗的名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NavRow(onBack = null, onNext = ::advance)
                }

                FirstCreateStage.USER_PROFILE -> {
                    Text("接下来是你自己的资料。它只会用于「怎么称呼你」和「怎么理解你」，不会被拿来推断别的。")
                    OutlinedTextField(
                        value = current.userAddress,
                        onValueChange = { value -> updateLatest { it.copy(userAddress = value) } },
                        label = { Text("希望它怎么称呼你") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.relationship,
                        onValueChange = { value -> updateLatest { it.copy(relationship = value) } },
                        label = { Text("它和你的关系（自己写，例如「一起住的小狗」）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "关系由你定义，应用不会替你加上专属、恋人或独占之类的设定。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("性别表述（只作为你的表述记录，永不会被用来推断任何事）")
                    GenderExpression.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = current.genderExpression == option,
                                onClick = { updateLatest { it.copy(genderExpression = option) } },
                            )
                            Text(genderLabel(option))
                        }
                    }
                    if (current.genderExpression == GenderExpression.CUSTOM) {
                        OutlinedTextField(
                            value = current.genderCustom,
                            onValueChange = { value -> updateLatest { it.copy(genderCustom = value) } },
                            label = { Text("你的表述") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = ageInput,
                        onValueChange = { value ->
                            val normalizedAge = value.filter { ch -> ch.isDigit() }.take(3)
                            ageInput = normalizedAge
                            updateLatest {
                                it.copy(
                                    declaredAge = normalizedAge.toIntOrNull(),
                                    declaredAgeRecordedAt = System.currentTimeMillis(),
                                )
                            }
                        },
                        label = { Text("年龄（只记录你填写的内容）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NavRow(onBack = ::back, onNext = ::advance)
                }

                FirstCreateStage.PERSONALITY_BRIEF -> {
                    Text("用你自己的话描述就行，不用写成规则。下面这些会交给模型，由它写成一份可执行的角色设定。")
                    OutlinedTextField(
                        value = current.desiredPersonality,
                        onValueChange = { value -> updateLatest { it.copy(desiredPersonality = value) } },
                        label = { Text("你希望它是什么样的（性格、习惯）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.desiredTowardUser,
                        onValueChange = { value -> updateLatest { it.copy(desiredTowardUser = value) } },
                        label = { Text("你希望它怎么看待你、怎么和你相处") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.desiredStyle,
                        onValueChange = { value -> updateLatest { it.copy(desiredStyle = value) } },
                        label = { Text("说话方式（可选，例如「短句、少用感叹号」）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.avoid,
                        onValueChange = { value -> updateLatest { it.copy(avoid = value) } },
                        label = { Text("你不希望出现的设定（可选）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NavRow(onBack = ::back, onNext = ::advance)
                }

                FirstCreateStage.MODEL_CHOICE -> {
                    Text("选择用哪个模型来写这份设定。这里只列出你已经验证过的服务。")
                    val ready = snapshot.connections.filter { it.ready }
                    if (ready.isEmpty()) {
                        Text(
                            "现在没有已验证的服务，请先配置并验证一家。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ready.forEach { connection ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(connection.label, fontWeight = FontWeight.SemiBold)
                                readinessReader.modelsFor(connection.provider).forEach { model ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = current.personaGeneratorProvider == connection.provider &&
                                                current.personaGeneratorModel == model,
                                            onClick = {
                                                updateLatest {
                                                    it.copy(
                                                        personaGeneratorProvider = connection.provider,
                                                        personaGeneratorModel = model,
                                                    )
                                                }
                                                status = null
                                            },
                                        )
                                        Text(model)
                                    }
                                }
                            }
                        }
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("正在生成角色设定…")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = ::back) { Text("上一步") }
                        Button(
                            enabled = !busy,
                            onClick = {
                                val source = draft ?: return@Button
                                val check = FirstCreateRules.validatePersonaGeneratorChoice(source)
                                if (check is FirstCreateValidation.Invalid) {
                                    status = check.reason
                                    return@Button
                                }
                                busy = true
                                status = null
                                notice = null
                                scope.launch {
                                    when (val generated = personaGenerator.generate(briefOf(source))) {
                                        is PersonaGenerationResult.Success -> {
                                            update(
                                                source.copy(
                                                    stage = FirstCreateStage.PERSONA_EDIT,
                                                    personaDraft = PersonaPromptSpec.renderConfirmedText(
                                                        briefOf(source),
                                                        generated.persona,
                                                    ),
                                                    personaRules = generated.persona.expressionDna,
                                                    personaFromFallback = false,
                                                    personaGeneratedAt = System.currentTimeMillis(),
                                                    personaEdited = false,
                                                ),
                                            )
                                        }

                                        is PersonaGenerationResult.Failure -> {
                                            // Never dead-end a newcomer on a model hiccup: fall
                                            // back to the neutral template and say so.
                                            notice = "${generated.reason} 已改用基础模板，你可以直接在上面修改。"
                                            update(
                                                source.copy(
                                                    stage = FirstCreateStage.PERSONA_EDIT,
                                                    personaDraft = NeutralPersonaTemplate.buildPersonaText(source),
                                                    personaRules = emptyList(),
                                                    personaFromFallback = true,
                                                    personaGeneratedAt = System.currentTimeMillis(),
                                                    personaEdited = false,
                                                ),
                                            )
                                        }
                                    }
                                    busy = false
                                }
                            },
                        ) { Text("生成设定") }
                    }
                }

                FirstCreateStage.PERSONA_GENERATING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在生成角色设定…")
                    }
                }

                FirstCreateStage.PERSONA_EDIT -> {
                    Text("这是根据你的描述生成的设定。你可以逐字修改，改到满意为止。")
                    if (current.personaFromFallback) {
                        Text(
                            "这次没有用上模型，当前是基础模板。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = current.personaDraft,
                        onValueChange = { value ->
                            updateLatest { it.copy(personaDraft = value, personaEdited = true) }
                        },
                        label = { Text("角色设定") },
                        minLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (current.personaRules.isNotEmpty()) {
                        Text("生成的行为规则", fontWeight = FontWeight.SemiBold)
                        current.personaRules.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            updateLatest { it.copy(stage = FirstCreateStage.MODEL_CHOICE) }
                        }) { Text("重新选择模型 / 重新生成") }
                    }
                    NavRow(onBack = null, onNext = ::advance)
                }

                FirstCreateStage.CONFIRM -> {
                    Text("确认前请再看一遍", style = MaterialTheme.typography.titleMedium)
                    InfoLine("小狗名字", current.petName)
                    InfoLine("对你的称呼", current.userAddress)
                    InfoLine("关系", current.relationship)
                    InfoLine("写设定的模型", current.personaGeneratorModel)
                    Text("角色设定", fontWeight = FontWeight.SemiBold)
                    Text(current.personaDraft, style = MaterialTheme.typography.bodySmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "确认后这份基础性格会锁定，App 里不再提供修改或重建入口。",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "之后只有你的资料可以更正。清除应用数据或卸载重装会一并清除本地状态。" +
                                    "如果还想改，请点「返回修改」。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = ::back) { Text("返回修改") }
                        Button(
                            enabled = !busy,
                            onClick = {
                                val source = draft ?: return@Button
                                busy = true
                                scope.launch {
                                    store.confirm(source)
                                        .onSuccess {
                                            draft = it
                                            busy = false
                                            onCompleted()
                                        }
                                        .onFailure {
                                            busy = false
                                            status = it.message ?: "确认失败，请重试。"
                                        }
                                }
                            },
                        ) { Text("确认并锁定") }
                    }
                }

                FirstCreateStage.COMPLETED -> {
                    Text("已经完成创建，角色设定已锁定。")
                    Button(onClick = onCompleted) { Text("开始聊天") }
                }
            }
        }
    }
}

/**
 * The only thing a brand-new install can do.
 *
 * A chat input is intentionally not rendered here: before a connection exists
 * there is nothing the app could answer with, and offering a text box that
 * cannot work is worse than offering none.
 */
@Composable
private fun ConnectionGateContent(
    snapshot: ConnectionSnapshot,
    busy: Boolean,
    onRecheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先测试模型连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "聊天需要一个可用的模型。填写你自己的 API Key 并测试通过后，才能开始创建小狗。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "支持的服务：MiniMax 官方、阿里百炼 DeepSeek。可以只配一家，也可以两家都配，之后还能给不同的 Agent 分别选模型。",
                style = MaterialTheme.typography.bodySmall,
            )
            snapshot.connections.forEach { connection ->
                val mark = when {
                    connection.ready -> "已验证，可以使用"
                    connection.hasKey -> "已填写 Key，但还没通过验证"
                    else -> "未配置"
                }
                Text("· ${connection.label}：$mark", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenSettings) { Text("配置 API Key") }
                OutlinedButton(enabled = !busy, onClick = onRecheck) { Text("重新检测") }
            }
        }
    }

    if (snapshot.hasUsableProvider) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("开始创建我的小狗")
        }
    } else {
        Text(
            "还没有可用的服务。请先点「配置 API Key」填写并验证其中一家。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NavRow(onBack: (() -> Unit)?, onNext: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        onBack?.let { OutlinedButton(onClick = it) { Text("上一步") } }
        Button(onClick = onNext) { Text("下一步") }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text("$label：", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun stageLabel(stage: FirstCreateStage): String = when (stage) {
    FirstCreateStage.CONNECTION_GATE -> "连接"
    FirstCreateStage.PET_NAME -> "小狗名字"
    FirstCreateStage.USER_PROFILE -> "你的资料"
    FirstCreateStage.PERSONALITY_BRIEF -> "你希望的设定"
    FirstCreateStage.MODEL_CHOICE -> "选择写设定的模型"
    FirstCreateStage.PERSONA_GENERATING -> "生成设定"
    FirstCreateStage.PERSONA_EDIT -> "修改设定"
    FirstCreateStage.CONFIRM -> "二次确认"
    FirstCreateStage.COMPLETED -> "已完成"
}

private fun genderLabel(option: GenderExpression): String = when (option) {
    GenderExpression.UNDISCLOSED -> "不透露"
    GenderExpression.FEMALE -> "女性"
    GenderExpression.MALE -> "男性"
    GenderExpression.OTHER -> "其他"
    GenderExpression.CUSTOM -> "自己填写"
}
