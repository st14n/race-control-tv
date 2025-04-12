package fr.groggy.racecontrol.tv.ui.signin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.f1.F1Credentials
import fr.groggy.racecontrol.tv.ui.home.HomeActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SignInActivity : ComponentActivity() {

    companion object {
        private val TAG = SignInActivity::class.simpleName

        fun intent(context: Context) = Intent(context, SignInActivity::class.java)

        fun intentClearTask(context: Context) = intent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    }

    @Inject lateinit var credentialsService: CredentialsService

    private val loginWebView: WebView by lazy { findViewById(R.id.login_webview) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signin)
        window.setSoftInputMode(SOFT_INPUT_STATE_VISIBLE or SOFT_INPUT_ADJUST_PAN)

        // Check if the user is already logged in using hasValidF1Credentials
        checkCredentialsAndNavigate()
    }

    private fun checkCredentialsAndNavigate() {
        lifecycleScope.launch {
            if (credentialsService.hasValidF1Credentials()) {
                navigateToHome()
            } else {
                setupWebView()
            }
        }
    }

    private fun setupWebView() {
        loginWebView.settings.domStorageEnabled = true
        loginWebView.settings.useWideViewPort = true
        loginWebView.settings.javaScriptEnabled = true

        // Add JavaScript interface to capture credentials
        loginWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun saveCredentials(username: String, password: String) {
                lifecycleScope.launch {
                    val credentials = F1Credentials(username, password, "")
                    credentialsService.saveCredentials(credentials)
                    Log.d(TAG, "Credentials saved for: $username")
                }
            }
        }, "Android")

        loginWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Check if the user is logged in by verifying cookies
                val cookies = CookieManager.getInstance().getCookie(url)
                if (credentialsService.storeToken(cookies)) {
                    navigateToHome()
                } else {
                    autoFillCredentials()
                    captureCredentials()
                }
            }
        }

        loginWebView.loadUrl("https://account.formula1.com/#/en/login")
    }

    private fun captureCredentials() {
        val jsCode = """
            javascript:(function() {
                // Listen for when sign in is clicked to capture the credentials
                var loginButton = document.querySelector('button.btn.btn-primary[type="submit"]');
                
                if (loginButton && !loginButton.hasAttribute('listener')) {
                    loginButton.setAttribute('listener', 'true');
                    loginButton.addEventListener('click', function() {
                        var login = document.querySelector('.txtLogin').value;
                        var password = document.querySelector('.txtPassword').value;
                        if (username && password) {
                            window.Android.saveCredentials(login, password);
                        }
                    });
                }
            })();
        """
        loginWebView.evaluateJavascript(jsCode, null)
    }

    private fun autoFillCredentials() {
        lifecycleScope.launch {
            val savedCredentials = credentialsService.getSavedCredentials()

            if (savedCredentials != null) {
                val jsCode = """
                javascript:(function() {
                    // Fill in the email field
                    var loginField = document.querySelector('.txtLogin');
                    if (loginField) {
                        loginField.value = '${savedCredentials.login}';
                    }
        
                    // Fill in the password field
                    var passwordField = document.querySelector('.txtPassword');
                    if (passwordField) {
                        passwordField.value = '${savedCredentials.password}';
                    }

                    // Click the "Sign In" button after a 1s delay
                    setTimeout(function() {
                        var loginButton = document.querySelector('button.btn.btn-primary[type="submit"]');
                        if (loginButton) {
                            loginButton.click();
                        }
                    }, 1000);
                })();
            """
                loginWebView.evaluateJavascript(jsCode, null)
            }
        }
    }


    private fun navigateToHome() {
        startActivity(HomeActivity.intent(this))
        finish()
    }
}
