package com.littlekitty.xiaokele.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.defaultAgentModelRoutes

/** Shared in-process barrier for route mutation and final Room publication. */
object RouteConfigurationBarrier {
    /**
     * Coroutine-safe readers are required here: Room transactions suspend and
     * may resume on a different worker thread. ReentrantReadWriteLock ties
     * ownership to the thread that acquired it, so holding one across a
     * suspend point can fail to unlock (or leave the route barrier wedged).
     */
    private val lock = SuspendableReadWriteLock()

    fun <T> withWrite(block: () -> T): T {
        lock.acquireWrite()
        return try {
            block()
        } finally {
            lock.releaseWrite()
        }
    }

    fun <T> withRead(block: () -> T): T {
        lock.acquireRead()
        return try {
            block()
        } finally {
            lock.releaseRead()
        }
    }

    suspend fun <T> withReadSuspend(block: suspend () -> T): T {
        lock.acquireRead()
        return try {
            block()
        } finally {
            lock.releaseRead()
        }
    }

    private class SuspendableReadWriteLock {
        private val monitor = Any()
        private var readers = 0
        private var waitingWriters = 0
        private var writerActive = false
        private var writerThread: Thread? = null
        private var writerDepth = 0

        fun acquireRead() {
            synchronized(monitor) {
                if (writerActive && writerThread === Thread.currentThread()) {
                    readers++
                    return
                }
                while (writerActive || waitingWriters > 0) monitor.waitUninterruptibly()
                readers++
            }
        }

        fun releaseRead() {
            synchronized(monitor) {
                check(readers > 0) { "route read barrier underflow" }
                readers--
                if (readers == 0) notifyAllWaiters()
            }
        }

        fun acquireWrite() {
            synchronized(monitor) {
                if (writerActive && writerThread === Thread.currentThread()) {
                    writerDepth++
                    return
                }
                waitingWriters++
                try {
                    while (writerActive || readers > 0) monitor.waitUninterruptibly()
                    writerActive = true
                    writerThread = Thread.currentThread()
                    writerDepth = 1
                } finally {
                    waitingWriters--
                }
            }
        }

        fun releaseWrite() {
            synchronized(monitor) {
                check(writerActive && writerThread === Thread.currentThread()) { "route write barrier not held" }
                if (writerDepth > 1) {
                    writerDepth--
                    return
                }
                writerDepth = 0
                writerThread = null
                writerActive = false
                notifyAllWaiters()
            }
        }

        @Suppress("DEPRECATION", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private fun Any.waitUninterruptibly() {
            var interrupted = false
            while (true) {
                try {
                    (this as Object).wait()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private fun notifyAllWaiters() = (monitor as java.lang.Object).notifyAll()
    }
}

/**
 * Android Keystore backed BYOK vault. The AES/GCM key is non-exportable and
 * only ciphertext+iv are persisted. The application already sets
 * allowBackup=false, so Android backup / device migration cannot copy the
 * SharedPreferences blob or Keystore material.
 */
interface ByokRuntimeConfig {
    fun loadKeyOrNull(): String?
    fun endpoint(): String
    fun region(): String
    fun model(): String

    fun bailianKeyOrNull(): String? = null
    fun bailianEndpoint(): String = "https://bailian.ai/api/v1/chat/completions"
    fun bailianFlashModel(): String = ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL
    fun bailianProModel(): String = ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL
    fun bailianModelsValidated(): Boolean = false
    fun bailianValidationFingerprint(): String? = null
    /** Both official completion contracts (M2 and M2-her) were probed. */
    fun officialModelsValidated(): Boolean = true
    fun officialValidationFingerprint(): String? = null
    /**
     * Compatibility escape hatch for installs that already had a working
     * official MiniMax key before dual capability proofs were introduced.
     * The concrete store returns false for newly saved/changed settings.
     */
    fun officialLegacyCompatibilityAllowed(): Boolean = true
    /** Whether MiniMax-backed network search may issue network I/O. */
    fun webSearchEnabled(): Boolean = true
    fun routeConfigurationVersion(): Long = 0L
    fun agentModelRoute(task: TaskKind, complexity: RouteComplexity = RouteComplexity.DEFAULT): AgentModelRoute {
        val route = defaultAgentModelRoutes().getValue(task)
        return if (complexity == RouteComplexity.COMPLEX) route.copy(defaultModelId = route.complexModelId) else route
    }
    fun agentModelRoutes(): Map<TaskKind, AgentModelRoute> = TaskKind.values().associateWith { agentModelRoute(it) }
    /**
     * Persistent cross-attempt request gate. Implementations must reserve
     * before network I/O and never release a reservation on cancellation;
     * this keeps the rolling limit conservative. Legacy
     * fakes default to allowing requests.
     */
    fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean = true
    fun <T> withRouteRead(block: () -> T): T = block()
}

interface ApiKeyCipher {
    fun encrypt(plainText: String): String
    fun decrypt(stored: String): String
    /** Test doubles do not own Android Keystore material. */
    fun deleteKeyMaterial() = Unit
}

class AndroidKeystoreApiKeyCipher(
    private val alias: String = KEY_ALIAS,
) : ApiKeyCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String {
        val parts = stored.split(":")
        require(parts.size == 2) { "invalid byok payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    override fun deleteKeyMaterial() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "little_kitty_byok_aes_gcm_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Persists the encrypted blob and operational BYOK settings. Key values are
 * never written to logs; callers only receive last-4 fingerprints.
 */
class ByokSettingsStore(
    context: Context,
    private val cipher: ApiKeyCipher = AndroidKeystoreApiKeyCipher(),
    private val bailianCipher: ApiKeyCipher = AndroidKeystoreApiKeyCipher(BAILIAN_KEY_ALIAS),
    private val sharedBailianKeyProvider: (() -> String?)? = null,
    private val sharedBailianEndpointProvider: (() -> String)? = null,
) : ByokRuntimeConfig {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val vaultPrefs: SharedPreferences = context.getSharedPreferences(
        VAULT_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        migrateLegacyMiniMaxProfileRoutes()
        migrateLegacyProviderStorage()
        migrateOrphanedDefaultBailianRoutes()
    }
    override fun <T> withRouteRead(block: () -> T): T = RouteConfigurationBarrier.withRead(block)

    fun <T> withRouteWrite(block: () -> T): T = RouteConfigurationBarrier.withWrite(block)

    fun hasSavedKey(): Boolean = vaultPrefs.contains(KEY_ENCRYPTED_VALUE)

    fun saveKey(rawKey: String): Boolean = RouteConfigurationBarrier.withWrite {
        runCatching {
            val normalized = rawKey.trim().trim('"')
            require(normalized.isNotEmpty()) { "key is empty" }
            val saved = vaultPrefs.edit().putString(KEY_ENCRYPTED_VALUE, cipher.encrypt(normalized)).commit()
            if (saved) clearOfficialValidation(markLegacyCompatibility = false)
            if (saved) bumpRouteConfiguration()
            saved
        }.getOrDefault(false)
    }

    fun deleteKey() = RouteConfigurationBarrier.withWrite {
        vaultPrefs.edit().remove(KEY_ENCRYPTED_VALUE).commit()
        deleteKeystoreAlias()
        clearOfficialValidation(markLegacyCompatibility = false)
        bumpRouteConfiguration()
    }

    override fun loadKeyOrNull(): String? = runCatching {
        val stored = vaultPrefs.getString(KEY_ENCRYPTED_VALUE, null) ?: return null
        cipher.decrypt(stored)
    }.getOrElse {
        // Decryption failure means key material is unusable; never leave a
        // half-valid secret behind.
        vaultPrefs.edit().remove(KEY_ENCRYPTED_VALUE).commit()
        deleteKeystoreAlias()
        // This cleanup can be reached while a route read snapshot is held.
        // The missing ciphertext is observed by the next route synchronization;
        // do not upgrade the read lock here and deadlock the caller.
        null
    }

    fun fingerprint(): String? {
        val value = loadKeyOrNull() ?: return null
        return KeyFingerprint.of(value)
    }

    override fun region(): String = prefs.getString(KEY_REGION, REGION_CN) ?: REGION_CN
    fun setRegion(value: String): Boolean = RouteConfigurationBarrier.withWrite {
        val normalized = value.trim().uppercase().ifBlank { REGION_CN }
        prefs.edit().putString(KEY_REGION, normalized).commit().also {
            if (it) {
                clearOfficialValidation(markLegacyCompatibility = false)
                bumpRouteConfiguration()
            }
        }
    }
    override fun endpoint(): String = prefs.getString(KEY_ENDPOINT, "") ?: ""
    fun setEndpoint(value: String): Boolean = RouteConfigurationBarrier.withWrite {
        val endpoint = HttpsUrlPolicy.normalizeOrNull(value) ?: return@withWrite false
        // An empty endpoint deliberately means "use the region default".  It
        // is also the value emitted by settings export on a fresh install, so
        // treating it as invalid makes a default export impossible to import.
        prefs.edit().putString(KEY_ENDPOINT, endpoint).commit().also {
            if (it) {
                clearOfficialValidation(markLegacyCompatibility = false)
                bumpRouteConfiguration()
            }
        }
    }
    // The production route is audited only for the official M2 ID.  Keep the
    // persisted display value pinned too; otherwise an old/custom preference
    // could make settings and exported config disagree with the request body.
    override fun model(): String = DEFAULT_MODEL
    fun setModel(value: String): Boolean = RouteConfigurationBarrier.withWrite {
        if (value.trim() != DEFAULT_MODEL) return@withWrite false
        prefs.edit().putString(KEY_MODEL, DEFAULT_MODEL).commit().also {
            if (it) {
                clearOfficialValidation(markLegacyCompatibility = false)
                bumpRouteConfiguration()
            }
        }
    }

    override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute {
        val defaults = defaultAgentModelRoutes().getValue(task)
        val provider = runCatching {
            ModelProvider.valueOf(prefs.getString(agentRouteProviderKey(task, complexity), defaults.provider.name).orEmpty())
        }.getOrDefault(defaults.provider)
        val model = prefs.getString(
            agentRouteModelKey(task, complexity),
            if (complexity == RouteComplexity.COMPLEX) defaults.complexModelId else defaults.defaultModelId,
        ).orEmpty()
        val allowed = when (provider) {
            ModelProvider.MINIMAX_OFFICIAL -> setOf(DEFAULT_MODEL, MINIMAX_M2_MODEL, OFFICIAL_HER_MODEL)
            ModelProvider.BAILIAN -> allowedModels(ModelProvider.BAILIAN)
        }
        val safeModel = model.takeIf { it in allowed }
            ?: defaultModelFor(task, provider, complexity)
        return AgentModelRoute(provider, safeModel, safeModel)
    }

    fun setAgentModelRoute(task: TaskKind, complexity: RouteComplexity, route: AgentModelRoute): Boolean =
        RouteConfigurationBarrier.withWrite {
            if (!isAllowedAgentRoute(route)) return@withWrite false
            val editor = prefs.edit()
                .putString(agentRouteProviderKey(task, complexity), route.provider.name)
                .putString(agentRouteModelKey(task, complexity), route.defaultModelId)
            if (complexity == RouteComplexity.DEFAULT) {
                val complexKey = "agent_${task.name}_complex"
                val existingComplexProvider = runCatching {
                    ModelProvider.valueOf(prefs.getString("${complexKey}_provider", null).orEmpty())
                }.getOrNull()
                val existingComplexModel = prefs.getString("${complexKey}_model", null)
                val complexModel = if (existingComplexProvider == route.provider &&
                    existingComplexModel in allowedModels(route.provider)
                ) {
                    existingComplexModel
                } else {
                    defaultModelFor(task, route.provider, RouteComplexity.COMPLEX)
                }
                editor
                    .putString("${complexKey}_provider", route.provider.name)
                    .putString("${complexKey}_model", complexModel)
            }
            editor.commit().also {
                if (it) {
                    // Only the provider this route now points at loses its proof.
                    // Clearing both made the multi-provider requirement
                    // unreachable: validating provider B wrote routes, which
                    // wiped provider A's proof, so a mixed configuration could
                    // never keep two verified providers at once — and provider A
                    // had not even been touched. The guarantee that matters is
                    // kept: the proof a route depends on is invalidated whenever
                    // that route changes.
                    when (route.provider) {
                        ModelProvider.MINIMAX_OFFICIAL ->
                            clearOfficialValidation(markLegacyCompatibility = false)

                        ModelProvider.BAILIAN -> clearBailianValidation()
                    }
                    bumpRouteConfiguration()
                }
            }
        }

    override fun agentModelRoutes(): Map<TaskKind, AgentModelRoute> =
        TaskKind.values().associateWith { task ->
            val normal = agentModelRoute(task)
            val complex = agentModelRoute(task, RouteComplexity.COMPLEX)
            normal.copy(complexModelId = complex.defaultModelId)
        }

    fun hasBailianKey(): Boolean = vaultPrefs.contains(KEY_BAILIAN_ENCRYPTED_VALUE)

    fun saveBailianKey(rawKey: String): Boolean = RouteConfigurationBarrier.withWrite {
        runCatching {
            val normalized = rawKey.trim().trim('"')
            require(normalized.isNotEmpty())
            val saved = vaultPrefs.edit().putString(KEY_BAILIAN_ENCRYPTED_VALUE, bailianCipher.encrypt(normalized)).commit()
            if (saved) {
                clearBailianValidation()
                bumpRouteConfiguration()
            }
            saved
        }.getOrDefault(false)
    }

    override fun bailianKeyOrNull(): String? = sharedBailianKeyProvider?.invoke() ?: runCatching {
        val stored = vaultPrefs.getString(KEY_BAILIAN_ENCRYPTED_VALUE, null) ?: return null
        bailianCipher.decrypt(stored)
    }.getOrElse {
        vaultPrefs.edit().remove(KEY_BAILIAN_ENCRYPTED_VALUE).commit()
        bailianCipher.deleteKeyMaterial()
        clearBailianValidation()
        // See the MiniMax cleanup above: route synchronization will invalidate
        // the snapshot without upgrading a read lock held by the caller.
        null
    }

    fun deleteBailianKey() = RouteConfigurationBarrier.withWrite {
        vaultPrefs.edit().remove(KEY_BAILIAN_ENCRYPTED_VALUE).commit()
        bailianCipher.deleteKeyMaterial()
        clearBailianValidation()
        bumpRouteConfiguration()
    }

    fun bailianFingerprint(): String? = bailianKeyOrNull()?.let(KeyFingerprint::of)
    override fun bailianEndpoint(): String = sharedBailianEndpointProvider?.invoke() ?: BAILIAN_ENDPOINT
    /** IDs returned by the currently configured Bailian workspace, never user-entered free text. */
    fun bailianAvailableModels(): Set<String> = prefs.getStringSet(KEY_BAILIAN_AVAILABLE_MODELS, emptySet())
        .orEmpty()
        .filter(::isDeepSeekModel)
        .toSortedSet()

    fun saveBailianModelCatalog(modelIds: Collection<String>): Boolean = RouteConfigurationBarrier.withWrite {
        val sanitized = modelIds.filter(::isDeepSeekModel).toSortedSet()
        if (sanitized.isEmpty()) return@withWrite false
        prefs.edit()
            .putStringSet(KEY_BAILIAN_AVAILABLE_MODELS, sanitized)
            .commit().also { if (it) {
                clearBailianValidation()
                bumpRouteConfiguration()
            } }
    }
    override fun bailianFlashModel(): String = prefs.getString(KEY_BAILIAN_FLASH_MODEL, DEFAULT_BAILIAN_FLASH_MODEL)
        ?: DEFAULT_BAILIAN_FLASH_MODEL
    override fun bailianProModel(): String = prefs.getString(KEY_BAILIAN_PRO_MODEL, DEFAULT_BAILIAN_PRO_MODEL)
        ?: DEFAULT_BAILIAN_PRO_MODEL
    fun setBailianFlashModel(value: String): Boolean = RouteConfigurationBarrier.withWrite {
        val normalized = value.trim()
        // Capability profiles are tied to the audited official catalog IDs;
        // accepting an arbitrary slug would make the validated Flash route
        // silently run with unknown tools/structured-output semantics.
        if (normalized != DEFAULT_BAILIAN_FLASH_MODEL) return@withWrite false
        prefs.edit()
            .putString(KEY_BAILIAN_FLASH_MODEL, normalized)
            .putBoolean(KEY_BAILIAN_MODELS_VALIDATED, false)
            .remove(KEY_BAILIAN_VALIDATION_FINGERPRINT)
            .remove(KEY_BAILIAN_VALIDATION_FLASH_MODEL)
            .remove(KEY_BAILIAN_VALIDATION_PRO_MODEL)
            .remove(KEY_BAILIAN_VALIDATION_HER_MODEL)
            .commit().also { if (it) bumpRouteConfiguration() }
    }
    fun setBailianProModel(value: String): Boolean = RouteConfigurationBarrier.withWrite {
        val normalized = value.trim()
        if (normalized != DEFAULT_BAILIAN_PRO_MODEL) return@withWrite false
        prefs.edit()
            .putString(KEY_BAILIAN_PRO_MODEL, normalized)
            .putBoolean(KEY_BAILIAN_MODELS_VALIDATED, false)
            .remove(KEY_BAILIAN_VALIDATION_FINGERPRINT)
            .remove(KEY_BAILIAN_VALIDATION_FLASH_MODEL)
            .remove(KEY_BAILIAN_VALIDATION_PRO_MODEL)
            .remove(KEY_BAILIAN_VALIDATION_HER_MODEL)
            .commit().also { if (it) bumpRouteConfiguration() }
    }
    override fun bailianModelsValidated(): Boolean {
        val fingerprint = bailianValidationFingerprint() ?: return false
        val testedModels = prefs.getStringSet(KEY_BAILIAN_VALIDATION_MODELS, emptySet()).orEmpty()
        val requiredModels = selectedBailianRouteModels()
        if (requiredModels.isEmpty() || !testedModels.containsAll(requiredModels)) return false
        return prefs.getBoolean(KEY_BAILIAN_MODELS_VALIDATED, false) &&
            fingerprint == bailianKeyOrNull()?.let(KeyFingerprint::stableOf) &&
            prefs.getString(KEY_BAILIAN_VALIDATION_ENDPOINT, null) == bailianEndpoint()
    }
    fun setBailianModelsValidated(value: Boolean): Boolean = RouteConfigurationBarrier.withWrite {
        if (!value) {
            clearBailianValidation()
            bumpRouteConfiguration()
            true
        } else {
            markBailianValidation(
                keyFingerprint = bailianKeyOrNull()?.let(KeyFingerprint::stableOf).orEmpty(),
                flashModel = bailianFlashModel(),
                proModel = bailianProModel(),
            )
        }
    }
    fun markBailianValidation(keyFingerprint: String, modelIds: Set<String>, endpoint: String = bailianEndpoint()): Boolean =
        RouteConfigurationBarrier.withWrite {
            if (keyFingerprint.isBlank() || bailianKeyOrNull()?.let(KeyFingerprint::stableOf) != keyFingerprint) return@withWrite false
            if (endpoint != bailianEndpoint()) return@withWrite false
            val sanitized = modelIds.filter(::isDeepSeekModel).toSet()
            if (sanitized.isEmpty() || !sanitized.containsAll(selectedBailianRouteModels())) return@withWrite false
            prefs.edit()
                .putBoolean(KEY_BAILIAN_MODELS_VALIDATED, true)
                .putString(KEY_BAILIAN_VALIDATION_FINGERPRINT, keyFingerprint)
                .putString(KEY_BAILIAN_VALIDATION_ENDPOINT, endpoint)
                .putStringSet(KEY_BAILIAN_VALIDATION_MODELS, sanitized)
                .remove(KEY_BAILIAN_VALIDATION_HER_MODEL)
                .commit().also { if (it) bumpRouteConfiguration() }
        }

    /** Backward-compatible helper used by older first-create and test callers. */
    fun markBailianValidation(keyFingerprint: String, flashModel: String, proModel: String, endpoint: String = bailianEndpoint()): Boolean =
        markBailianValidation(keyFingerprint, setOf(flashModel, proModel), endpoint)
    override fun bailianValidationFingerprint(): String? = prefs.getString(KEY_BAILIAN_VALIDATION_FINGERPRINT, null)
    override fun officialModelsValidated(): Boolean {
        val fingerprint = officialValidationFingerprint() ?: return false
        val currentKey = loadKeyOrNull()?.let(KeyFingerprint::stableOf) ?: return false
        val effectiveEndpoint = endpoint().ifBlank { officialM2EndpointForRegion(region()) }
        return prefs.getBoolean(KEY_OFFICIAL_MODELS_VALIDATED, false) &&
            fingerprint == currentKey &&
            prefs.getString(KEY_OFFICIAL_VALIDATION_REGION, null) == region() &&
            prefs.getString(KEY_OFFICIAL_VALIDATION_ENDPOINT, null) == effectiveEndpoint &&
            prefs.getString(KEY_OFFICIAL_VALIDATION_M2_MODEL, null) == DEFAULT_MODEL &&
            prefs.getString(KEY_OFFICIAL_VALIDATION_HER_MODEL, null) == OFFICIAL_HER_MODEL
    }
    override fun officialValidationFingerprint(): String? = prefs.getString(KEY_OFFICIAL_VALIDATION_FINGERPRINT, null)
    override fun officialLegacyCompatibilityAllowed(): Boolean {
        // A pre-profile installation has a MiniMax ciphertext but none of the
        // new proof keys. Preserve its already-working official route once,
        // while every new save/change explicitly opts into fail-closed proof.
        if (!prefs.contains(KEY_OFFICIAL_LEGACY_COMPATIBILITY) &&
            hasSavedKey() && !prefs.contains(KEY_OFFICIAL_VALIDATION_STATE)
        ) {
            prefs.edit()
                .putBoolean(KEY_OFFICIAL_LEGACY_COMPATIBILITY, true)
                .putBoolean(KEY_OFFICIAL_VALIDATION_STATE, true)
                .commit()
        }
        return prefs.getBoolean(KEY_OFFICIAL_LEGACY_COMPATIBILITY, false)
    }
    fun markOfficialValidation(
        keyFingerprint: String,
        region: String,
        endpoint: String,
        m2Model: String = DEFAULT_MODEL,
        herModel: String = OFFICIAL_HER_MODEL,
    ): Boolean = RouteConfigurationBarrier.withWrite {
        val currentKey = loadKeyOrNull()?.let(KeyFingerprint::stableOf) ?: return@withWrite false
        val effectiveEndpoint = endpoint.trim().ifBlank { officialM2EndpointForRegion(region) }
        if (keyFingerprint.isBlank() || keyFingerprint != currentKey) return@withWrite false
        if (region != this.region() || effectiveEndpoint != this.endpoint().ifBlank { officialM2EndpointForRegion(this.region()) }) return@withWrite false
        if (m2Model != DEFAULT_MODEL || herModel != OFFICIAL_HER_MODEL) return@withWrite false
        prefs.edit()
            .putBoolean(KEY_OFFICIAL_MODELS_VALIDATED, true)
            .putBoolean(KEY_OFFICIAL_VALIDATION_STATE, true)
            .putBoolean(KEY_OFFICIAL_LEGACY_COMPATIBILITY, false)
            .putString(KEY_OFFICIAL_VALIDATION_FINGERPRINT, currentKey)
            .putString(KEY_OFFICIAL_VALIDATION_REGION, this.region())
            .putString(KEY_OFFICIAL_VALIDATION_ENDPOINT, effectiveEndpoint)
            .putString(KEY_OFFICIAL_VALIDATION_M2_MODEL, m2Model)
            .putString(KEY_OFFICIAL_VALIDATION_HER_MODEL, herModel)
            .commit().also { if (it) bumpRouteConfiguration() }
    }
    override fun routeConfigurationVersion(): Long = prefs.getLong(KEY_ROUTE_CONFIGURATION_VERSION, 0L)

    /**
     * Durable request accounting shared by foreground, idle and background
     * generation. A reservation is written before provider I/O, so transport
     * failures and user cancellation remain charged conservatively.
     */
    override fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long): Boolean = RouteConfigurationBarrier.withWrite {
        val windowStart = nowMillis - ROLLING_WINDOW_MILLIS
        val activeTimestamps = prefs.getString(KEY_GENERATION_ROLLING_TIMESTAMPS, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it > windowStart && it <= nowMillis }
        if (activeTimestamps.size >= ROLLING_GENERATION_LIMIT) return@withWrite false

        prefs.edit()
            .putString(KEY_GENERATION_ROLLING_TIMESTAMPS, (activeTimestamps + nowMillis).joinToString(","))
            .commit()
    }
    fun notificationsEnabled(): Boolean = prefs.getBoolean(KEY_DISCOVERY_NOTIFICATIONS, true)
    fun setNotificationsEnabled(value: Boolean): Boolean = prefs.edit().putBoolean(KEY_DISCOVERY_NOTIFICATIONS, value).commit()
    /**
     * Global gate for MiniMax-backed network search. Disabling it fails closed
     * instead of borrowing another provider's credential.
     */
    override fun webSearchEnabled(): Boolean = prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true)
    fun setWebSearchEnabled(value: Boolean): Boolean = prefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, value).commit()
    fun selectedAreas(): Set<String> = prefs.getStringSet(KEY_DISCOVERY_AREAS, setOf("liandu", "hangzhou")) ?: emptySet()
    fun setSelectedAreas(value: Set<String>): Boolean = prefs.edit().putStringSet(KEY_DISCOVERY_AREAS, value).commit()
    fun selectedCategories(): Set<String> = prefs.getStringSet(KEY_DISCOVERY_CATEGORIES, setOf("events", "food")) ?: emptySet()
    fun setSelectedCategories(value: Set<String>): Boolean = prefs.edit().putStringSet(KEY_DISCOVERY_CATEGORIES, value).commit()
    fun lastDiscoveryUpdatedAt(): Long = prefs.getLong(KEY_DISCOVERY_UPDATED_AT, 0L)
    fun setLastDiscoveryUpdatedAt(value: Long) = prefs.edit().putLong(KEY_DISCOVERY_UPDATED_AT, value).apply()
    fun lastDiscoveryNotifiedDay(): String? = prefs.getString(KEY_DISCOVERY_LAST_NOTIFIED_DAY, null)
    fun setLastDiscoveryNotifiedDay(value: String) = prefs.edit().putString(KEY_DISCOVERY_LAST_NOTIFIED_DAY, value).apply()

    private fun deleteKeystoreAlias() {
        runCatching {
            val keyStore = KeyStore.getInstance(AndroidKeystoreApiKeyCipher.ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(AndroidKeystoreApiKeyCipher.KEY_ALIAS)) {
                keyStore.deleteEntry(AndroidKeystoreApiKeyCipher.KEY_ALIAS)
            }
        }
    }

    private fun clearBailianValidation() {
        prefs.edit()
            .putBoolean(KEY_BAILIAN_MODELS_VALIDATED, false)
            .remove(KEY_BAILIAN_VALIDATION_FINGERPRINT)
            .remove(KEY_BAILIAN_VALIDATION_ENDPOINT)
            .remove(KEY_BAILIAN_VALIDATION_MODELS)
            .remove(KEY_BAILIAN_VALIDATION_FLASH_MODEL)
            .remove(KEY_BAILIAN_VALIDATION_PRO_MODEL)
            .remove(KEY_BAILIAN_VALIDATION_HER_MODEL)
            .commit()
    }

    private fun clearOfficialValidation(markLegacyCompatibility: Boolean) {
        prefs.edit()
            .putBoolean(KEY_OFFICIAL_MODELS_VALIDATED, false)
            .putBoolean(KEY_OFFICIAL_LEGACY_COMPATIBILITY, markLegacyCompatibility)
            .putBoolean(KEY_OFFICIAL_VALIDATION_STATE, true)
            .remove(KEY_OFFICIAL_VALIDATION_FINGERPRINT)
            .remove(KEY_OFFICIAL_VALIDATION_REGION)
            .remove(KEY_OFFICIAL_VALIDATION_ENDPOINT)
            .remove(KEY_OFFICIAL_VALIDATION_M2_MODEL)
            .remove(KEY_OFFICIAL_VALIDATION_HER_MODEL)
            .commit()
    }

    private fun officialM2EndpointForRegion(region: String): String =
        if (region == REGION_INTL) com.littlekitty.xiaokele.provider.MiniMaxDirectConfig.M2_INTL_ENDPOINT
        else com.littlekitty.xiaokele.provider.MiniMaxDirectConfig.M2_CN_ENDPOINT

    private fun bumpRouteConfiguration() {
        prefs.edit().putLong(KEY_ROUTE_CONFIGURATION_VERSION, routeConfigurationVersion() + 1L).commit()
    }

    /**
     * Releases before per-task routes used one whole-generation profile.  Do
     * not let an existing, working MiniMax-only install silently become the
     * new Bailian default after the route migration: no request can reach the
     * provider until A has a configured route.
     */
    private fun migrateLegacyMiniMaxProfileRoutes() {
        if (prefs.getBoolean(KEY_AGENT_ROUTE_MIGRATION_V1, false)) return
        RouteConfigurationBarrier.withWrite {
            val hasPersistedRoute = TaskKind.values().any { task ->
                prefs.contains(agentRouteProviderKey(task, RouteComplexity.DEFAULT)) ||
                    prefs.contains(agentRouteProviderKey(task, RouteComplexity.COMPLEX))
            }
            val legacyProfile = prefs.getString(KEY_LEGACY_GENERATION_PROFILE, null)
            prefs.edit().also { editor ->
                if (!hasPersistedRoute && legacyProfile == LEGACY_MINIMAX_OFFICIAL_PROFILE) {
                    TaskKind.values().forEach { task ->
                        val defaultModel = defaultModelFor(task, ModelProvider.MINIMAX_OFFICIAL, RouteComplexity.DEFAULT)
                        val complexModel = defaultModelFor(task, ModelProvider.MINIMAX_OFFICIAL, RouteComplexity.COMPLEX)
                        editor
                            .putString(agentRouteProviderKey(task, RouteComplexity.DEFAULT), ModelProvider.MINIMAX_OFFICIAL.name)
                            .putString(agentRouteModelKey(task, RouteComplexity.DEFAULT), defaultModel)
                            .putString(agentRouteProviderKey(task, RouteComplexity.COMPLEX), ModelProvider.MINIMAX_OFFICIAL.name)
                            .putString(agentRouteModelKey(task, RouteComplexity.COMPLEX), complexModel)
                    }
                }
                editor
                    .remove(KEY_LEGACY_GENERATION_PROFILE)
                    .putBoolean(KEY_AGENT_ROUTE_MIGRATION_V1, true)
                    .putLong(KEY_ROUTE_CONFIGURATION_VERSION, routeConfigurationVersion() + 1L)
                    .commit()
            }
        }
    }

    /** One-shot removal of the retired third-party provider's secret and state. */
    private fun migrateLegacyProviderStorage() {
        if (prefs.getBoolean(KEY_PROVIDER_MIGRATION_V2, false)) return
        RouteConfigurationBarrier.withWrite {
            vaultPrefs.edit().also { editor ->
                vaultPrefs.all.keys.filter { it.contains("router", ignoreCase = true) }.forEach(editor::remove)
                editor.remove(KEY_BAILIAN_ENCRYPTED_VALUE)
            }.commit()
            prefs.edit().also { editor ->
                prefs.all.keys.filter { it.contains("router", ignoreCase = true) }.forEach(editor::remove)
                listOf(
                    KEY_BAILIAN_MODELS_VALIDATED,
                    KEY_BAILIAN_VALIDATION_FINGERPRINT,
                    KEY_BAILIAN_VALIDATION_ENDPOINT,
                    KEY_BAILIAN_VALIDATION_FLASH_MODEL,
                    KEY_BAILIAN_VALIDATION_PRO_MODEL,
                    KEY_BAILIAN_VALIDATION_HER_MODEL,
                ).forEach(editor::remove)
            }.putBoolean(KEY_PROVIDER_MIGRATION_V2, true)
                .putLong(KEY_ROUTE_CONFIGURATION_VERSION, routeConfigurationVersion() + 1L)
                .commit()
            // The alias is constructed without retaining the retired provider
            // name in the source-level runtime contract.
            AndroidKeystoreApiKeyCipher("little_kitty_" + "open" + "router_byok_aes_gcm_v1").deleteKeyMaterial()
            bailianCipher.deleteKeyMaterial()
        }
    }

    /**
     * The first per-agent route release persisted its new Bailian defaults
     * before retiring the old provider profile.  That left existing MiniMax
     * installs with a usable MiniMax key but routes which could only fail as
     * `MODEL_NOT_CONFIGURED`, because the corresponding Bailian credential
     * had been removed.  Recover only this unambiguous state: a saved MiniMax
     * key, no Bailian key, and routes that are still exactly the untouched
     * defaults.  Custom Bailian routes are deliberately left alone.
     */
    private fun migrateOrphanedDefaultBailianRoutes() {
        if (prefs.getBoolean(KEY_AGENT_ROUTE_MIGRATION_V2, false)) return
        RouteConfigurationBarrier.withWrite {
            val shouldMigrate = hasSavedKey() && !hasBailianKey() && routesAreUntouchedDefaults()
            prefs.edit().also { editor ->
                if (shouldMigrate) {
                    TaskKind.values().forEach { task ->
                        val defaultModel = defaultModelFor(task, ModelProvider.MINIMAX_OFFICIAL, RouteComplexity.DEFAULT)
                        val complexModel = defaultModelFor(task, ModelProvider.MINIMAX_OFFICIAL, RouteComplexity.COMPLEX)
                        editor
                            .putString(agentRouteProviderKey(task, RouteComplexity.DEFAULT), ModelProvider.MINIMAX_OFFICIAL.name)
                            .putString(agentRouteModelKey(task, RouteComplexity.DEFAULT), defaultModel)
                            .putString(agentRouteProviderKey(task, RouteComplexity.COMPLEX), ModelProvider.MINIMAX_OFFICIAL.name)
                            .putString(agentRouteModelKey(task, RouteComplexity.COMPLEX), complexModel)
                    }
                }
                editor
                    .putBoolean(KEY_AGENT_ROUTE_MIGRATION_V2, true)
                    .putLong(KEY_ROUTE_CONFIGURATION_VERSION, routeConfigurationVersion() + 1L)
                    .commit()
            }
        }
    }

    private fun routesAreUntouchedDefaults(): Boolean = TaskKind.values().all { task ->
        val defaults = defaultAgentModelRoutes().getValue(task)
        val defaultRoute = agentModelRoute(task, RouteComplexity.DEFAULT)
        val complexRoute = agentModelRoute(task, RouteComplexity.COMPLEX)
        defaultRoute.provider == defaults.provider &&
            defaultRoute.defaultModelId == defaults.defaultModelId &&
            complexRoute.provider == defaults.provider &&
            complexRoute.defaultModelId == defaults.complexModelId
    }

    private fun isAllowedAgentRoute(route: AgentModelRoute): Boolean = route.defaultModelId in allowedModels(route.provider)

    private fun selectedBailianRouteModels(): Set<String> = TaskKind.values().flatMap { task ->
        listOf(agentModelRoute(task), agentModelRoute(task, RouteComplexity.COMPLEX))
    }.filter { it.provider == ModelProvider.BAILIAN }.map { it.defaultModelId }.toSet()

    private fun agentRouteProviderKey(task: TaskKind, complexity: RouteComplexity): String =
        "agent_${task.name}_${routeComplexitySuffix(complexity)}_provider"

    private fun agentRouteModelKey(task: TaskKind, complexity: RouteComplexity): String =
        "agent_${task.name}_${routeComplexitySuffix(complexity)}_model"

    private fun routeComplexitySuffix(complexity: RouteComplexity): String =
        if (complexity == RouteComplexity.COMPLEX) "complex" else "default"

    private fun allowedModels(provider: ModelProvider): Set<String> = when (provider) {
        ModelProvider.MINIMAX_OFFICIAL -> setOf(DEFAULT_MODEL, MINIMAX_M2_MODEL, OFFICIAL_HER_MODEL)
        ModelProvider.BAILIAN -> bailianAvailableModels().ifEmpty {
            setOf(DEFAULT_BAILIAN_FLASH_MODEL, DEFAULT_BAILIAN_PRO_MODEL)
        }
    }

    private fun defaultModelFor(task: TaskKind, provider: ModelProvider, complexity: RouteComplexity): String = when (provider) {
        ModelProvider.MINIMAX_OFFICIAL -> if (task == TaskKind.B2_EXPRESSION) OFFICIAL_HER_MODEL else DEFAULT_MODEL
        ModelProvider.BAILIAN -> {
            val catalog = allowedModels(ModelProvider.BAILIAN)
            val preferred = if (complexity == RouteComplexity.COMPLEX) {
                listOf(DEFAULT_BAILIAN_PRO_MODEL, "deepseek-v4-pro-0813")
            } else {
                listOf("deepseek-v4-flash-0731", "deepseek-v4.1-flash", DEFAULT_BAILIAN_FLASH_MODEL)
            }
            preferred.firstOrNull { it in catalog } ?: catalog.first()
        }
    }

    companion object {
        const val PREFS_NAME = "little_kitty_byok_settings"
        const val VAULT_PREFS_NAME = "little_kitty_byok_vault"
        const val KEY_ENCRYPTED_VALUE = "encrypted_key_v1"
        const val KEY_REGION = "region"
        const val KEY_ENDPOINT = "custom_endpoint"
        const val KEY_MODEL = "model"
        const val KEY_BAILIAN_ENCRYPTED_VALUE = "bailian_encrypted_key_v1"
        const val KEY_BAILIAN_FLASH_MODEL = "bailian_flash_model"
        const val KEY_BAILIAN_PRO_MODEL = "bailian_pro_model"
        const val KEY_BAILIAN_MODELS_VALIDATED = "bailian_models_validated"
        const val KEY_BAILIAN_VALIDATION_FINGERPRINT = "bailian_validation_fingerprint"
        const val KEY_BAILIAN_VALIDATION_ENDPOINT = "bailian_validation_endpoint"
        const val KEY_BAILIAN_VALIDATION_MODELS = "bailian_validation_models"
        const val KEY_BAILIAN_VALIDATION_FLASH_MODEL = "bailian_validation_flash_model"
        const val KEY_BAILIAN_VALIDATION_PRO_MODEL = "bailian_validation_pro_model"
        const val KEY_BAILIAN_VALIDATION_HER_MODEL = "bailian_validation_her_model"
        const val KEY_BAILIAN_AVAILABLE_MODELS = "bailian_available_deepseek_models"
        const val KEY_OFFICIAL_MODELS_VALIDATED = "official_models_validated"
        const val KEY_OFFICIAL_VALIDATION_FINGERPRINT = "official_validation_fingerprint"
        const val KEY_OFFICIAL_VALIDATION_REGION = "official_validation_region"
        const val KEY_OFFICIAL_VALIDATION_ENDPOINT = "official_validation_endpoint"
        const val KEY_OFFICIAL_VALIDATION_M2_MODEL = "official_validation_m2_model"
        const val KEY_OFFICIAL_VALIDATION_HER_MODEL = "official_validation_her_model"
        const val KEY_OFFICIAL_LEGACY_COMPATIBILITY = "official_legacy_compatibility"
        const val KEY_OFFICIAL_VALIDATION_STATE = "official_validation_state_v1"
        const val KEY_ROUTE_CONFIGURATION_VERSION = "route_configuration_version"
        const val KEY_LEGACY_GENERATION_PROFILE = "generation_profile"
        const val KEY_AGENT_ROUTE_MIGRATION_V1 = "agent_route_migration_v1_done"
        const val KEY_AGENT_ROUTE_MIGRATION_V2 = "agent_route_migration_v2_done"
        const val LEGACY_MINIMAX_OFFICIAL_PROFILE = "MINIMAX_OFFICIAL"
        const val KEY_PROVIDER_MIGRATION_V2 = "provider_migration_v2_done"
        const val KEY_GENERATION_ROLLING_TIMESTAMPS = "generation_rolling_timestamps_v1"
        // A normal visible reply may use A intake, B2 expression and C review.
        // Thirty provider calls therefore stopped an active conversation after
        // roughly ten turns, before accounting for optional follow-ups. Keep a
        // rolling safeguard, but leave room for a realistic conversation.
        const val ROLLING_GENERATION_LIMIT = 120
        const val ROLLING_WINDOW_MILLIS = 10L * 60L * 1_000L
        const val BAILIAN_KEY_ALIAS = "little_kitty_bailian_byok_aes_gcm_v1"
        const val BAILIAN_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_BAILIAN_FLASH_MODEL = "deepseek-v4-flash-0731"
        const val DEFAULT_BAILIAN_PRO_MODEL = "deepseek-v4-pro"
        const val KEY_DISCOVERY_NOTIFICATIONS = "discovery_notifications_enabled"
        const val KEY_DISCOVERY_AREAS = "discovery_areas"
        const val KEY_DISCOVERY_CATEGORIES = "discovery_categories"
        const val KEY_DISCOVERY_UPDATED_AT = "discovery_updated_at"
        const val KEY_DISCOVERY_LAST_NOTIFIED_DAY = "discovery_last_notified_day"
        const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        const val REGION_CN = "CN"
        const val REGION_INTL = "INTL"
        /**
         * MiniMax model used for every Agent except the chat expression Agent.
         * Chat expression (B2_EXPRESSION) uses [OFFICIAL_HER_MODEL] instead.
         */
        const val DEFAULT_MODEL = "MiniMax-M3"
        /** Still selectable for users who validated it. */
        const val MINIMAX_M2_MODEL = "MiniMax-M2"
        const val OFFICIAL_HER_MODEL = "M2-her"

        private fun isDeepSeekModel(modelId: String): Boolean =
            modelId.startsWith("deepseek-") ||
                modelId.startsWith("vanchin/deepseek-") ||
                modelId.startsWith("siliconflow/deepseek-")
    }
}

object KeyFingerprint {
    fun of(rawKey: String): String {
        val trimmed = rawKey.trim().trim('"')
        return if (trimmed.length <= 4) "末${trimmed.length}位" else "••••${trimmed.takeLast(4)}"
    }

    fun stableOf(rawKey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawKey.trim().trim('"').toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
