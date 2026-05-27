package fr.groggy.racecontrol.tv.core.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val credentialsService: CredentialsService
): ViewModel() {
    fun applySettings() = settingsRepository.applySettings()

    fun resetSettings() = settingsRepository.resetSettings()

    fun logout() {
        settingsRepository.clearAccountCredentials()
        credentialsService.clearCredentials()
    }

    suspend fun getCurrentAccountInfo(): CurrentAccountInfo {
        val settings = settingsRepository.getCurrent()
        val accountEmail = when {
            settings.f1Username.isNotBlank() -> settings.f1Username
            else -> credentialsService.getSavedCredentials()?.login?.takeIf { it.isNotBlank() }
                ?: BuildConfig.F1_USERNAME.takeIf { it.isNotBlank() }
        }

        return CurrentAccountInfo(
            email = accountEmail,
            isLoggedIn = credentialsService.hasValidF1Credentials()
        )
    }

    data class CurrentAccountInfo(
        val email: String?,
        val isLoggedIn: Boolean
    )
}