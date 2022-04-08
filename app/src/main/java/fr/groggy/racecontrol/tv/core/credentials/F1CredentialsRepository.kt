package fr.groggy.racecontrol.tv.core.credentials

import fr.groggy.racecontrol.tv.f1.F1Credentials
import fr.groggy.racecontrol.tv.f1.F1Token

interface F1CredentialsRepository {

    fun find(): F1Credentials?

    fun save(credentials: F1Credentials)

    fun saveToken(token: String)

    fun getToken(): F1Token?

    fun delete()

}