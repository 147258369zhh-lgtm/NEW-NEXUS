package io.github.mangi.eta.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceFontFamily
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.MessageTypographySettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.ModelCandidateSettings
import io.github.mangi.eta.data.model.ModelRoleSettings
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.model.CloudToolsSettings
import io.github.mangi.eta.data.model.AutomationModelSettings
import kotlinx.serialization.json.Json
import io.github.mangi.eta.data.model.Settings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal object SettingsDataStore {
    private val SPEECH_SETTINGS = stringPreferencesKey("speech_settings_v1")
    private val WEB_TOOLS_SETTINGS = stringPreferencesKey("web_tools_settings_v1")
    private val CLOUD_TOOLS_SETTINGS = stringPreferencesKey("cloud_tools_settings_v1")
    private val SUBAGENT_SETTINGS = stringPreferencesKey("subagent_settings_v1")
    private val MODEL_FALLBACK_SETTINGS = stringPreferencesKey("model_fallback_settings_v1")
    private val MODEL_CANDIDATE_SETTINGS = stringPreferencesKey("model_candidate_settings_v1")
    private val AUTOMATION_MODEL_SETTINGS = stringPreferencesKey("automation_model_settings_v1")
    private val MODEL_ROLE_SETTINGS = stringPreferencesKey("model_role_settings_v1")
    private val AGENT_PERMISSION_MODE = stringPreferencesKey("agent_permission_mode_v1")
    private val speechJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun speechSettingsFlow(): Flow<SpeechSettings> {
        ensureInitialized()
        return dataStore.data.map { prefs ->
            prefs[SPEECH_SETTINGS]?.let { speechJson.decodeFromString<SpeechSettings>(it) }
                ?: SpeechSettings()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    suspend fun speechSettings() = speechSettingsFlow().first()

    suspend fun setSpeechSettings(settings: SpeechSettings) {
        ensureInitialized()
        dataStore.edit { it[SPEECH_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun webToolsSettingsFlow(): Flow<WebToolsSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { prefs ->
                prefs[WEB_TOOLS_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<WebToolsSettings>(raw) }.getOrNull()
                } ?: WebToolsSettings()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun webToolsSettings(): WebToolsSettings = webToolsSettingsFlow().first()

    suspend fun setWebToolsSettings(settings: WebToolsSettings) {
        ensureInitialized()
        dataStore.edit { it[WEB_TOOLS_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun cloudToolsSettingsFlow(): Flow<CloudToolsSettings> {
        ensureInitialized()
        return dataStore.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { prefs ->
            prefs[CLOUD_TOOLS_SETTINGS]?.let { speechJson.decodeFromString<CloudToolsSettings>(it) }
                ?: CloudToolsSettings()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    suspend fun cloudToolsSettings(): CloudToolsSettings = cloudToolsSettingsFlow().first()

    suspend fun setCloudToolsSettings(settings: CloudToolsSettings) {
        ensureInitialized()
        dataStore.edit { it[CLOUD_TOOLS_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun subagentSettingsFlow(): Flow<SubagentSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { prefs ->
                prefs[SUBAGENT_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<SubagentSettings>(raw) }.getOrNull()
                } ?: SubagentSettings()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun subagentSettings(): SubagentSettings = subagentSettingsFlow().first()

    suspend fun setSubagentSettings(settings: SubagentSettings) {
        ensureInitialized()
        dataStore.edit { it[SUBAGENT_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun modelFallbackSettingsFlow(): Flow<ModelFallbackSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs ->
                prefs[MODEL_FALLBACK_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<ModelFallbackSettings>(raw).normalized() }.getOrNull()
                } ?: ModelFallbackSettings()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun modelFallbackSettings(): ModelFallbackSettings = modelFallbackSettingsFlow().first()

    suspend fun setModelFallbackSettings(settings: ModelFallbackSettings) {
        ensureInitialized()
        dataStore.edit { it[MODEL_FALLBACK_SETTINGS] = speechJson.encodeToString(settings.normalized()) }
    }

    fun modelCandidateSettingsFlow(): Flow<ModelCandidateSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs ->
                prefs[MODEL_CANDIDATE_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<ModelCandidateSettings>(raw).normalized() }.getOrNull()
                } ?: ModelCandidateSettings()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun modelCandidateSettings(): ModelCandidateSettings = modelCandidateSettingsFlow().first()

    suspend fun setModelCandidateSettings(settings: ModelCandidateSettings) {
        ensureInitialized()
        dataStore.edit { it[MODEL_CANDIDATE_SETTINGS] = speechJson.encodeToString(settings.normalized()) }
    }

    fun automationModelSettingsFlow(): Flow<AutomationModelSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs ->
                prefs[AUTOMATION_MODEL_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<AutomationModelSettings>(raw) }.getOrNull()
                } ?: AutomationModelSettings()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun automationModelSettings(): AutomationModelSettings = automationModelSettingsFlow().first()

    suspend fun setAutomationModelSettings(settings: AutomationModelSettings) {
        ensureInitialized()
        dataStore.edit { it[AUTOMATION_MODEL_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun modelRoleSettingsFlow(): Flow<ModelRoleSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs ->
                prefs[MODEL_ROLE_SETTINGS]?.let { raw ->
                    runCatching {
                        speechJson.decodeFromString<ModelRoleSettings>(raw).normalized()
                    }.getOrNull()
                } ?: ModelRoleSettings()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun modelRoleSettings(): ModelRoleSettings = modelRoleSettingsFlow().first()

    suspend fun setModelRoleSettings(settings: ModelRoleSettings) {
        ensureInitialized()
        dataStore.edit { it[MODEL_ROLE_SETTINGS] = speechJson.encodeToString(settings.normalized()) }
    }

    fun agentPermissionModeFlow(): Flow<AgentPermissionMode> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs -> AgentPermissionMode.fromWireValue(prefs[AGENT_PERMISSION_MODE]) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun agentPermissionMode(): AgentPermissionMode = agentPermissionModeFlow().first()

    suspend fun setAgentPermissionMode(mode: AgentPermissionMode) {
        ensureInitialized()
        dataStore.edit {
            val revision = (it[AGENT_PERMISSION_REVISION] ?: 0L) + 1L
            it[AGENT_PERMISSION_MODE] = mode.wireValue
            it[AGENT_PERMISSION_REVISION] = revision
            if (mode == AgentPermissionMode.SMART_APPROVAL) it[AGENT_PERMISSION_SMART_RESTRICTION] = revision
            if (mode == AgentPermissionMode.MANUAL_APPROVAL) it[AGENT_PERMISSION_MANUAL_RESTRICTION] = revision
        }
    }

    suspend fun agentPermissionState(): io.github.mangi.eta.data.model.AgentPermissionState {
        ensureInitialized()
        val prefs = dataStore.data.first()
        return io.github.mangi.eta.data.model.AgentPermissionState(
            AgentPermissionMode.fromWireValue(prefs[AGENT_PERMISSION_MODE]), prefs[AGENT_PERMISSION_REVISION] ?: 0L,
            prefs[AGENT_PERMISSION_SMART_RESTRICTION] ?: 0L, prefs[AGENT_PERMISSION_MANUAL_RESTRICTION] ?: 0L,
        )
    }

    private val AGENT_PERMISSION_REVISION = androidx.datastore.preferences.core.longPreferencesKey("agent_permission_revision_v1")
    private val AGENT_PERMISSION_SMART_RESTRICTION = androidx.datastore.preferences.core.longPreferencesKey("agent_permission_smart_restriction_v1")
    private val AGENT_PERMISSION_MANUAL_RESTRICTION = androidx.datastore.preferences.core.longPreferencesKey("agent_permission_manual_restriction_v1")

    private const val STORE_NAME = "eta_settings"

    private val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
    private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    private val OFFICIAL_MODEL_CATALOG_REVISION = intPreferencesKey("official_model_catalog_revision")
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
    private val LINUX_DISTRIBUTION = stringPreferencesKey("linux_distribution")
    private val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    private val APPEARANCE_SKIN = stringPreferencesKey("appearance_skin")
    private val APPEARANCE_MONET_ENABLED = booleanPreferencesKey("appearance_monet_enabled")
    private val APPEARANCE_PALETTE_STYLE = stringPreferencesKey("appearance_palette_style")
    private val APPEARANCE_ACCENT_COLOR = stringPreferencesKey("appearance_accent_color")
    private val APPEARANCE_PURE_BLACK_ENABLED = booleanPreferencesKey("appearance_pure_black_enabled")
    private val APPEARANCE_BLUR_ENABLED = booleanPreferencesKey("appearance_blur_enabled")
    private val APPEARANCE_TOP_BAR_BLUR_STYLE = stringPreferencesKey("appearance_top_bar_blur_style")
    private val APPEARANCE_SWIPE_DISMISS_ENABLED =
        booleanPreferencesKey("appearance_swipe_dismiss_enabled")
    private val APPEARANCE_PREDICTIVE_BACK_ENABLED =
        booleanPreferencesKey("appearance_predictive_back_enabled")
    private val APPEARANCE_INTERFACE_SCALE = floatPreferencesKey("appearance_interface_scale")
    private val APPEARANCE_FONT_FAMILY = stringPreferencesKey("appearance_font_family")
    private val APPEARANCE_GLASS_OPACITY_MULTIPLIER = floatPreferencesKey("appearance_glass_opacity_multiplier")
    private val APPEARANCE_GLASS_BLUR_MULTIPLIER = floatPreferencesKey("appearance_glass_blur_multiplier")
    private val APPEARANCE_GLASS_REFRACTION_ENABLED = booleanPreferencesKey("appearance_glass_refraction_enabled")
    private val APPEARANCE_PRESS_FEEDBACK_ENABLED = booleanPreferencesKey("appearance_press_feedback_enabled")
    private val APPEARANCE_HOME_MODE_SWIPE_ENABLED = booleanPreferencesKey("appearance_home_mode_swipe_enabled")
    private val APPEARANCE_HOME_MODE_SWIPE_THRESHOLD_DP = floatPreferencesKey("appearance_home_mode_swipe_threshold_dp")
    private val MESSAGE_TYPOGRAPHY = stringPreferencesKey("message_typography_v1")
    private val APPEARANCE_DEFAULT_SKIN_VERSION = intPreferencesKey("appearance_default_skin_version")
    private const val SELECTED_MODEL_BY_PROVIDER_PREFIX = "selected_model_id_by_provider."

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var appContext: Context
    private var storePath: String? = null
    private var storeScope: CoroutineScope? = null

    internal fun applicationContext(): Context {
        ensureInitialized()
        return appContext
    }

    @Synchronized
    fun init(context: Context) {
        val application = context.applicationContext
        val file = application.preferencesDataStoreFile(STORE_NAME)
        val path = file.absolutePath
        if (storePath == path && ::dataStore.isInitialized) {
            appContext = application
            return
        }
        storeScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        storeScope = scope
        storePath = path
        appContext = application
        io.github.mangi.eta.data.repository.AppearanceSettingsRepository.invalidateStartupSnapshot()
    }

    fun settingsFlow(): Flow<Settings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { preferences -> preferences.toSettings() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun settings(): Settings = settingsFlow().first()

    suspend fun officialModelCatalogRevision(): Int {
        ensureInitialized()
        return dataStore.data.first()[OFFICIAL_MODEL_CATALOG_REVISION] ?: 0
    }

    suspend fun setOfficialModelCatalogRevision(revision: Int) {
        ensureInitialized()
        dataStore.edit { preferences ->
            preferences[OFFICIAL_MODEL_CATALOG_REVISION] = revision
        }
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val updated = transform(current)
            prefs.putOrRemove(SELECTED_PROVIDER_ID, updated.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, updated.selectedModelId)
            prefs[MEMORY_ENABLED] = updated.memoryEnabled
            prefs.putAppearance(updated.appearance.normalized())
        }
    }

    fun selectedProviderIdFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { preferences -> preferences[SELECTED_PROVIDER_ID] }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    fun selectedModelIdFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { preferences -> preferences[SELECTED_MODEL_ID] }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    suspend fun selectedModelIdForProvider(providerId: String): String? {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> prefs[selectedModelByProviderKey(providerId)] }
            .first()
    }

    fun memoryEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryEnabled }

    fun linuxDistributionFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { preferences -> preferences[LINUX_DISTRIBUTION] }
    }

    fun linuxBackendFlow(distribution: String): Flow<String?> {
        ensureInitialized()
        return dataStore.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[stringPreferencesKey("linux_backend.$distribution")] }
    }

    suspend fun setLinuxBackend(distribution: String, backend: String?) {
        ensureInitialized()
        dataStore.edit { preferences ->
            val key = stringPreferencesKey("linux_backend.$distribution")
            if (backend == null) preferences.remove(key) else preferences[key] = backend
        }
    }

    fun appearanceSettingsFlow(): Flow<AppearanceSettings> =
        settingsFlow().map { it.appearance }

    suspend fun setSelectedProviderId(id: String?) {
        updateSettings { it.copy(selectedProviderId = id) }
    }

    suspend fun setSelectedModelId(id: String?) {
        updateSettings { it.copy(selectedModelId = id) }
    }

    suspend fun setSelection(providerId: String?, modelId: String?) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val previousProviderId = prefs[SELECTED_PROVIDER_ID]
            val previousModelId = prefs[SELECTED_MODEL_ID]
            val currentMemory = providerId?.let { prefs[selectedModelByProviderKey(it)] }
            if (previousProviderId == providerId && previousModelId == modelId &&
                (providerId == null || modelId == null || currentMemory == modelId)
            ) {
                return@edit
            }
            if (previousProviderId != null && previousModelId != null) {
                prefs[selectedModelByProviderKey(previousProviderId)] = previousModelId
            }

            prefs.putOrRemove(SELECTED_PROVIDER_ID, providerId)
            prefs.putOrRemove(SELECTED_MODEL_ID, modelId)
            if (providerId != null && modelId != null) {
                prefs[selectedModelByProviderKey(providerId)] = modelId
            }
        }
    }

    suspend fun clearSelectedModelIdForProvider(providerId: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.remove(selectedModelByProviderKey(providerId))
        }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryEnabled = enabled) }
    }

    suspend fun setLinuxDistribution(value: String?) {
        ensureInitialized()
        dataStore.edit { preferences -> preferences.putOrRemove(LINUX_DISTRIBUTION, value) }
    }

    suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        updateSettings { it.copy(appearance = settings.normalized()) }
    }

    suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        updateSettings { settings ->
            settings.copy(appearance = transform(settings.appearance).normalized())
        }
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "SettingsDataStore.init(context) must be called in Application.onCreate()"
        }
    }

    private fun selectedModelByProviderKey(providerId: String): Preferences.Key<String> =
        stringPreferencesKey("$SELECTED_MODEL_BY_PROVIDER_PREFIX$providerId")

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        selectedProviderId = this[SELECTED_PROVIDER_ID],
        selectedModelId = this[SELECTED_MODEL_ID],
        memoryEnabled = this[MEMORY_ENABLED] ?: true,
        appearance = AppearanceSettings(
            skin = if (this[APPEARANCE_SKIN] == null && (this[APPEARANCE_DEFAULT_SKIN_VERSION] ?: 0) < CURRENT_APPEARANCE_DEFAULT_SKIN_VERSION) {
                AppearanceSkin.CLASSIC
            } else AppearanceSkin.fromPersistedValue(this[APPEARANCE_SKIN]),
            themeMode = AppearanceThemeMode.fromPersistedValue(this[APPEARANCE_THEME_MODE]),
            monetEnabled = this[APPEARANCE_MONET_ENABLED] ?: false,
            paletteStyle = AppearancePaletteStyle.fromPersistedValue(this[APPEARANCE_PALETTE_STYLE]),
            accentColor = AppearanceAccentColor.fromPersistedValue(this[APPEARANCE_ACCENT_COLOR]),
            pureBlackEnabled = this[APPEARANCE_PURE_BLACK_ENABLED] ?: false,
            blurEnabled = this[APPEARANCE_BLUR_ENABLED] ?: true,
            topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(
                this[APPEARANCE_TOP_BAR_BLUR_STYLE],
            ),
            swipeDismissEnabled = this[APPEARANCE_SWIPE_DISMISS_ENABLED] ?: true,
            predictiveBackEnabled = this[APPEARANCE_PREDICTIVE_BACK_ENABLED] ?: true,
            interfaceScale = this[APPEARANCE_INTERFACE_SCALE] ?: 1f,
            fontFamily = AppearanceFontFamily.fromPersistedValue(this[APPEARANCE_FONT_FAMILY]),
            glassOpacityMultiplier = this[APPEARANCE_GLASS_OPACITY_MULTIPLIER] ?: 1f,
            glassBlurMultiplier = this[APPEARANCE_GLASS_BLUR_MULTIPLIER] ?: 1f,
            glassRefractionEnabled = this[APPEARANCE_GLASS_REFRACTION_ENABLED] ?: true,
            pressFeedbackEnabled = this[APPEARANCE_PRESS_FEEDBACK_ENABLED] ?: true,
            homeModeSwipeEnabled = this[APPEARANCE_HOME_MODE_SWIPE_ENABLED] ?: true,
            homeModeSwipeThresholdDp = this[APPEARANCE_HOME_MODE_SWIPE_THRESHOLD_DP] ?: 56f,
            messageTypography = this[MESSAGE_TYPOGRAPHY]?.let { raw ->
                runCatching { speechJson.decodeFromString<MessageTypographySettings>(raw).normalized() }.getOrNull()
            } ?: MessageTypographySettings(),
        ).normalized(),
    )

    private fun MutablePreferences.putAppearance(settings: AppearanceSettings) {
        this[APPEARANCE_SKIN] = settings.skin.persistedValue
        this[APPEARANCE_THEME_MODE] = settings.themeMode.persistedValue
        this[APPEARANCE_MONET_ENABLED] = settings.monetEnabled
        this[APPEARANCE_PALETTE_STYLE] = settings.paletteStyle.persistedValue
        this[APPEARANCE_ACCENT_COLOR] = settings.accentColor.persistedValue
        this[APPEARANCE_PURE_BLACK_ENABLED] = settings.pureBlackEnabled
        this[APPEARANCE_BLUR_ENABLED] = settings.blurEnabled
        this[APPEARANCE_TOP_BAR_BLUR_STYLE] = settings.topBarBlurStyle.persistedValue
        this[APPEARANCE_SWIPE_DISMISS_ENABLED] = settings.swipeDismissEnabled
        this[APPEARANCE_PREDICTIVE_BACK_ENABLED] = settings.predictiveBackEnabled
        this[APPEARANCE_INTERFACE_SCALE] = settings.interfaceScale
        this[APPEARANCE_FONT_FAMILY] = settings.fontFamily.persistedValue
        this[APPEARANCE_GLASS_OPACITY_MULTIPLIER] = settings.glassOpacityMultiplier
        this[APPEARANCE_GLASS_BLUR_MULTIPLIER] = settings.glassBlurMultiplier
        this[APPEARANCE_GLASS_REFRACTION_ENABLED] = settings.glassRefractionEnabled
        this[APPEARANCE_PRESS_FEEDBACK_ENABLED] = settings.pressFeedbackEnabled
        this[APPEARANCE_HOME_MODE_SWIPE_ENABLED] = settings.homeModeSwipeEnabled
        this[APPEARANCE_HOME_MODE_SWIPE_THRESHOLD_DP] = settings.homeModeSwipeThresholdDp
        this[MESSAGE_TYPOGRAPHY] = speechJson.encodeToString(settings.messageTypography.normalized())
        this[APPEARANCE_DEFAULT_SKIN_VERSION] = CURRENT_APPEARANCE_DEFAULT_SKIN_VERSION
    }

    private const val CURRENT_APPEARANCE_DEFAULT_SKIN_VERSION = 2
}
