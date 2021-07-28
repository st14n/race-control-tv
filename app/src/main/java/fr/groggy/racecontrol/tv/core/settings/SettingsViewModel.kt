package fr.groggy.racecontrol.tv.core.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val credentialsService: CredentialsService
): ViewModel() {
    fun applySettings() = settingsRepository.applySettings()

    fun resetSettings() = settingsRepository.resetSettings()

    fun logout() = credentialsService.clearCredentials()
}