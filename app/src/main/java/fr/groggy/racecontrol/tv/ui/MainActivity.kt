package fr.groggy.racecontrol.tv.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.ui.home.HomeActivity
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject internal lateinit var credentialsService: CredentialsService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startHomeActivity()
    }

    private fun startHomeActivity() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val intent = when {
                    !credentialsService.hasValidF1Credentials() ->
                        SignInActivity.intent(this@MainActivity)
                    credentialsService.shouldReLogin() ->
                        SignInActivity.intentSilentReAuth(this@MainActivity)
                    else ->
                        HomeActivity.intent(this@MainActivity)
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
