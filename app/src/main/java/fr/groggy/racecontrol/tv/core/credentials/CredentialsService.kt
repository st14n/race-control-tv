package fr.groggy.racecontrol.tv.core.credentials

import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.f1.F1Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialsService @Inject constructor(
    private val f1CredentialsRepository: F1CredentialsRepository,
    moshi: Moshi
) {
    private val cookieTokenAdapter = moshi.adapter(F1LoginToken::class.java)

    suspend fun hasValidF1Credentials(): Boolean = withContext(Dispatchers.IO) {
        val token = f1CredentialsRepository.getToken() ?: return@withContext false
        return@withContext token.isValid()
    }

    fun getToken() = f1CredentialsRepository.getToken()

    fun clearCredentials() = f1CredentialsRepository.delete()

    suspend fun getSavedCredentials(): F1Credentials? = withContext(Dispatchers.IO) {
        return@withContext f1CredentialsRepository.find()
    }

    suspend fun saveCredentials(credentials: F1Credentials) = withContext(Dispatchers.IO) {
        return@withContext f1CredentialsRepository.save(credentials)
    }

    fun storeToken(cookie: String?): Boolean {
        try {
            if (cookie?.contains("login-session") == true) {
                val loginToken = cookie.substringAfter("login-session=")
                    .substringBefore("; ")
                val decodedToken = URLDecoder.decode(loginToken, "UTF-8") ?: return false
                val token = cookieTokenAdapter.fromJson(decodedToken) ?: return false
                f1CredentialsRepository.saveToken(token.data.subscriptionToken)
                f1CredentialsRepository.saveLastLoginTimestamp(System.currentTimeMillis())
                return true
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }

    fun shouldReLogin(): Boolean {
        val lastLogin = f1CredentialsRepository.getLastLoginTimestamp()
        if (lastLogin == 0L) return true
        return System.currentTimeMillis() - lastLogin >= fr.groggy.racecontrol.tv.BuildConfig.TOKEN_REFRESH_INTERVAL_MS
    }
}
