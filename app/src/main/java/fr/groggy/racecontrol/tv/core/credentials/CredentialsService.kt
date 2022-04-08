package fr.groggy.racecontrol.tv.core.credentials

import com.squareup.moshi.Moshi
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

    fun storeToken(cookie: String?): Boolean {
        try {
            if (cookie?.contains("login-session") == true) {
                val loginToken = cookie.substringAfter("login-session=")
                    .substringBefore("; ")
                val decodedToken = URLDecoder.decode(loginToken, "UTF-8") ?: return false
                val token = cookieTokenAdapter.fromJson(decodedToken) ?: return false
                f1CredentialsRepository.saveToken(token.data.subscriptionToken)
                return true
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }
}
