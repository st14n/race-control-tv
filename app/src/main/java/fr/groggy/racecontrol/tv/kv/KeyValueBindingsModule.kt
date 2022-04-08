package fr.groggy.racecontrol.tv.kv

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.groggy.racecontrol.tv.core.credentials.F1CredentialsRepository
import fr.groggy.racecontrol.tv.kv.credentials.SharedPreferencesF1CredentialsRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class KeyValueBindingsModule {

    @Binds
    abstract fun f1CredentialsRepository(repository: SharedPreferencesF1CredentialsRepository): F1CredentialsRepository

}
