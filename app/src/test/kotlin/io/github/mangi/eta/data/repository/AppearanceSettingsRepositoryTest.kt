package io.github.mangi.eta.data.repository

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.selectedOrFirstModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import android.content.ContextWrapper
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class AppearanceSettingsRepositoryTest {
    @Test
    fun reinitializingWithAnotherDataDirectoryUsesThatDirectory() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "settings-isolation-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = directory
        }
        val original = SettingsDataStore.settings().appearance
        try {
            SettingsDataStore.init(isolated)
            AppearanceSettingsRepository.update { it.copy(monetEnabled = true) }
            assertEquals(true, AppearanceSettingsRepository.settings().monetEnabled)
            SettingsDataStore.init(app)
            assertEquals(original, AppearanceSettingsRepository.settings())
        } finally {
            SettingsDataStore.init(app)
        }
    }
    @Test
    fun startupFlowObservesChangesMadeOutsideAppearanceRepository() = runBlocking {
        val before = SettingsDataStore.settings().appearance
        try {
            AppearanceSettingsRepository.settings()
            val mode = if (before.themeMode == AppearanceThemeMode.DARK) AppearanceThemeMode.LIGHT else AppearanceThemeMode.DARK
            SettingsDataStore.setAppearanceSettings(before.copy(themeMode = mode))
            assertEquals(mode, AppearanceSettingsRepository.settingsFlow().first().themeMode)
        } finally {
            SettingsDataStore.setAppearanceSettings(before)
        }
    }
    @Test
    fun appearanceRoundTripPreservesExistingSettingsFields() = runBlocking {
        val before = SettingsDataStore.settings()
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.allProviders().firstOrNull { it.isEnabled }
        val providerId = provider?.id
        val modelId = provider?.selectedOrFirstModel(null)?.id
        val appearance = AppearanceSettings(
            skin = AppearanceSkin.OMNI,
            themeMode = AppearanceThemeMode.DARK,
            monetEnabled = true,
            accentColor = AppearanceAccentColor.TEAL,
            interfaceScale = 0.9f,
        )

        try {
            SettingsDataStore.updateSettings {
                it.copy(
                    selectedProviderId = providerId,
                    selectedModelId = modelId,
                    memoryEnabled = false,
                )
            }
            AppearanceSettingsRepository.update(appearance)

            val expected = appearance.copy(skin = AppearanceSkin.CLASSIC)
            assertEquals(expected, AppearanceSettingsRepository.settingsFlow().first())
            assertEquals(expected, AppearanceSettingsRepository.settings())
            val after = SettingsDataStore.settings()
            assertEquals(providerId, after.selectedProviderId)
            assertEquals(modelId, after.selectedModelId)
            assertEquals(false, after.memoryEnabled)
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun transformUpdatesDoNotOverwriteEarlierAppearanceChanges() = runBlocking {
        val before = AppearanceSettingsRepository.settings()
        try {
            AppearanceSettingsRepository.update { it.copy(monetEnabled = true) }
            AppearanceSettingsRepository.update { it.copy(blurEnabled = false) }

            val actual = AppearanceSettingsRepository.settings()
            assertEquals(true, actual.monetEnabled)
            assertEquals(false, actual.blurEnabled)
        } finally {
            AppearanceSettingsRepository.update(before)
        }
    }

    @Test
    fun explicitLegacySkinSurvivesLaterAppearanceUpdates() = runBlocking {
        val original = SettingsDataStore.settings().appearance
        try {
            AppearanceSettingsRepository.update { it.copy(skin = AppearanceSkin.CLASSIC) }
            AppearanceSettingsRepository.update { it.copy(blurEnabled = false) }
            assertEquals(AppearanceSkin.CLASSIC, AppearanceSettingsRepository.settings().skin)
        } finally {
            AppearanceSettingsRepository.update(original)
        }
    }

    @Test
    fun legacyAndCurrentGlassSkinsUseCurrentGlassAfterEffectChanges() = runBlocking {
        val original = SettingsDataStore.settings()
        try {
            // Match a real configured profile, after built-in provider initialization.
            ProviderRepository.ensureBuiltInsMerged()
            val provider = ProviderRepository.allProviders().first { it.isEnabled }
            SettingsDataStore.updateSettings {
                it.copy(selectedProviderId = provider.id, selectedModelId = provider.selectedOrFirstModel(null)?.id)
            }
            val configured = SettingsDataStore.settings()
            for (skin in listOf(AppearanceSkin.FROST_GLASS, AppearanceSkin.LIQUID_GLASS)) {
                AppearanceSettingsRepository.update {
                    it.copy(skin = skin, blurEnabled = true, themeMode = AppearanceThemeMode.DARK)
                }
                AppearanceSettingsRepository.update { it.copy(blurEnabled = false) }
                val stored = SettingsDataStore.settings()
                assertEquals(AppearanceSkin.LIQUID_GLASS, stored.appearance.skin)
                assertEquals(false, stored.appearance.blurEnabled)
                assertEquals(AppearanceThemeMode.DARK, stored.appearance.themeMode)
                assertEquals(configured.selectedProviderId, stored.selectedProviderId)
                assertEquals(configured.selectedModelId, stored.selectedModelId)
                assertEquals(configured.memoryEnabled, stored.memoryEnabled)
            }
        } finally {
            SettingsDataStore.updateSettings { original }
        }
    }
}
