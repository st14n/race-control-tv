package fr.groggy.racecontrol.tv.core.credentials

import com.auth0.android.jwt.JWT
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
    companion object {
        private const val LOGIN_SESSION_COOKIE = "login-session"
    }

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

    enum class CookieTokenResult {
        /** A token with enough remaining validity was found and stored. */
        STORED,
        /** The cookies hold a token, but it's expired or about to expire; a fresh login is needed. */
        STALE,
        MISSING
    }

    /**
     * Looks through all cookies for one holding `{"data":{"subscriptionToken":...}}` (currently `login-session`),
     * so a renamed cookie doesn't break login. The WebView keeps that cookie after the token inside it expires,
     * so tokens expiring within [minRemainingValidityMs] are reported as [CookieTokenResult.STALE] instead of stored.
     */
    fun storeToken(cookie: String?, minRemainingValidityMs: Long = 0): CookieTokenResult {
        val subscriptionToken = cookie.orEmpty()
            .split(";")
            .map { it.trim().substringBefore("=") to it.trim().substringAfter("=", "") }
            .sortedByDescending { (name, _) -> name == LOGIN_SESSION_COOKIE }
            .firstNotNullOfOrNull { (_, value) -> parseSubscriptionToken(value) }
            ?: return CookieTokenResult.MISSING

        val expiresAt = try {
            JWT(subscriptionToken).expiresAt?.time
        } catch (e: Exception) {
            null
        }
        if (expiresAt == null || expiresAt - System.currentTimeMillis() < minRemainingValidityMs) {
            return CookieTokenResult.STALE
        }

        f1CredentialsRepository.saveToken(subscriptionToken)
        f1CredentialsRepository.saveLastLoginTimestamp(System.currentTimeMillis())
        return CookieTokenResult.STORED
    }

    private fun parseSubscriptionToken(cookieValue: String): String? {
        return try {
            val decodedValue = URLDecoder.decode(cookieValue, "UTF-8")
            if (!decodedValue.trimStart().startsWith("{")) {
                return null
            }
            cookieTokenAdapter.fromJson(decodedValue)?.data?.subscriptionToken?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun shouldReLogin(): Boolean {
        val lastLogin = f1CredentialsRepository.getLastLoginTimestamp()
        if (lastLogin == 0L) return true
        return System.currentTimeMillis() - lastLogin >= fr.groggy.racecontrol.tv.BuildConfig.TOKEN_REFRESH_INTERVAL_MS
    }
}
