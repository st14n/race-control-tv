package fr.groggy.racecontrol.tv.kv.credentials

import com.auth0.android.jwt.JWT
import fr.groggy.racecontrol.tv.core.credentials.F1CredentialsRepository
import fr.groggy.racecontrol.tv.f1.F1Credentials
import fr.groggy.racecontrol.tv.f1.F1Token
import fr.groggy.racecontrol.tv.kv.SharedPreferencesStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPreferencesF1CredentialsRepository @Inject constructor(
    private val store: SharedPreferencesStore
) : F1CredentialsRepository {

    companion object {
        private const val LOGIN_KEY = "F1_LOGIN"
        private const val PASSWORD_KEY = "F1_PASSWORD"
        private const val LOGIN_TOKEN = "LOGIN_TOKEN"
    }

    override fun find(): F1Credentials? {
        val login = store.findString(LOGIN_KEY)
        val password = store.findString(PASSWORD_KEY)
        val loginToken = store.findString(LOGIN_TOKEN)
        return if (login != null && password != null && loginToken != null) {
            F1Credentials(
                login = login,
                password = password,
                subToken = loginToken
            )
        } else {
            null
        }
    }

    override fun save(credentials: F1Credentials) =
        store.update {
            putString(LOGIN_KEY, credentials.login)
            putString(PASSWORD_KEY, credentials.password)
        }

    override fun saveToken(token: String) {
        store.update {
            putString(LOGIN_TOKEN, token)
        }
    }

    override fun getToken(): F1Token? {
        val token = store.findString(LOGIN_TOKEN) ?: return null
        return F1Token(JWT(token))
    }

    override fun delete() =
        store.update {
            remove(LOGIN_KEY)
            remove(PASSWORD_KEY)
            remove(LOGIN_TOKEN)
        }
}