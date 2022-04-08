package fr.groggy.racecontrol.tv.core.token

import android.util.Log
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.f1.F1Token
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenService @Inject constructor(
    private val credentialsService: CredentialsService
) {

    companion object {
        private val TAG = TokenService::class.simpleName
    }

    fun getToken(): F1Token {
        Log.d(TAG, "loadAndGetF1Token")
        return credentialsService.getToken() ?: throw RuntimeException("No token available")
    }
}
