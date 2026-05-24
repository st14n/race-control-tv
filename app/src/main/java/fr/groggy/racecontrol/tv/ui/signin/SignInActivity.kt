//package fr.groggy.racecontrol.tv.ui.signin
//
//import android.content.Context
//import android.content.Intent
//import android.os.Bundle
//import android.util.Log
//import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
//import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
//import android.webkit.CookieManager
//import android.webkit.WebView
//import android.webkit.WebViewClient
//import androidx.activity.ComponentActivity
//import androidx.lifecycle.lifecycleScope
//import dagger.hilt.android.AndroidEntryPoint
//import fr.groggy.racecontrol.tv.R
//import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
//import fr.groggy.racecontrol.tv.ui.home.HomeActivity
//import kotlinx.coroutines.launch
//import javax.inject.Inject
//
//@AndroidEntryPoint
//class SignInActivity : ComponentActivity() {
//
//    companion object {
//        private val TAG = SignInActivity::class.simpleName
//
//        fun intent(context: Context) = Intent(context, SignInActivity::class.java)
//
//        fun intentClearTask(context: Context) = intent(context).apply {
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
//        }
//    }
//
//    @Inject lateinit var credentialsService: CredentialsService
//
//    private val loginWebView: WebView by lazy { findViewById(R.id.login_webview) }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        Log.d(TAG, "onCreate")
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_signin)
//        window.setSoftInputMode(SOFT_INPUT_STATE_VISIBLE or SOFT_INPUT_ADJUST_PAN)
//
//    // Check if the user is already logged in using hasValidF1Credentials
//        checkCredentialsAndNavigate()
//    }
//
//    private fun checkCredentialsAndNavigate() {
//        lifecycleScope.launch {
//            if (credentialsService.hasValidF1Credentials()) {
//                navigateToHome()
//            } else {
//                setupWebView()
//            }
//        }
//    }
//
//    private fun setupWebView() {
//        loginWebView.settings.domStorageEnabled = true
//        loginWebView.settings.useWideViewPort = true
//        loginWebView.settings.javaScriptEnabled = true
//
//        loginWebView.webViewClient = object : WebViewClient() {
//            override fun onPageFinished(view: WebView?, url: String?) {
//                super.onPageFinished(view, url)
//
//                // Check if the user is logged in by verifying cookies
//                // This is necessary as otherwise it will show you the account page rather than the race catalog
//                val cookies = CookieManager.getInstance().getCookie(url)
//                if (credentialsService.storeToken(cookies)) {
//                    navigateToHome()
//                } else {
//                    autoFillCredentials()
//                }
//            }
//        }
//
//        loginWebView.loadUrl("https://account.formula1.com/#/en/login")
//    }
//
//    private fun autoFillCredentials() {
//        val jsCode = """
//        javascript:(function() {
//            // Fill in the email field
//            document.querySelector('.txtLogin').value = 'hardcodedusername';
//
//            // Fill in the password field
//            document.querySelector('.txtPassword').value = 'hardcodedpassword';
//
//            // Click the "Sign In" button after a short delay
//            setTimeout(function() {
//            document.querySelector('button.btn.btn-primary[type="submit"]').click();
//            }, 1000);
//        })();
//        """
//        loginWebView.evaluateJavascript(jsCode, null)
//    }
//
//    private fun navigateToHome() {
//        startActivity(HomeActivity.intent(this))
//        finish()
//    }
//}



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
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1.F1Credentials // Ensure this is imported
import fr.groggy.racecontrol.tv.ui.home.HomeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SignInActivity : ComponentActivity() {

    companion object {
        private val TAG = SignInActivity::class.simpleName
        private const val F1_LOGIN_URL = "https://account.formula1.com/#/en/login"
        private const val EXTRA_SILENT_REAUTH = "silent_reauth"

        fun intent(context: Context) = Intent(context, SignInActivity::class.java)

        fun intentClearTask(context: Context) = intent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        /** Triggered when the token is still valid but 6 hours have elapsed — re-authenticates
         *  silently using a hidden WebView so the user stays on the home screen. */
        fun intentSilentReAuth(context: Context) = intent(context).apply {
            putExtra(EXTRA_SILENT_REAUTH, true)
        }
    }

    @Inject lateinit var credentialsService: CredentialsService
    @Inject lateinit var settingsRepository: SettingsRepository

    private val loginWebView: WebView by lazy { findViewById(R.id.login_webview) }
    private val reauthOverlay: android.widget.TextView by lazy { findViewById(R.id.reauth_overlay) }

    private var isSilentReAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signin)
        window.setSoftInputMode(SOFT_INPUT_STATE_VISIBLE or SOFT_INPUT_ADJUST_PAN)

        isSilentReAuth = intent.getBooleanExtra(EXTRA_SILENT_REAUTH, false)
        if (isSilentReAuth) {
            // Hide the WebView; show a quiet overlay so the user sees a brief refresh screen
            loginWebView.visibility = android.view.View.INVISIBLE
            reauthOverlay.visibility = android.view.View.VISIBLE
            Log.d(TAG, "Silent re-auth mode: WebView hidden.")
            // Safety net: if auth doesn't complete within 30 s, navigate home anyway
            lifecycleScope.launch {
                delay(30_000)
                if (!isFinishing) {
                    Log.w(TAG, "Silent re-auth timed out — proceeding to home")
                    navigateToHome()
                }
            }
        }

        lifecycleScope.launch { setupWebView() }
    }

    private fun setupWebView() {
        Log.d(TAG, "Setting up WebView")
        loginWebView.settings.domStorageEnabled = true
        loginWebView.settings.useWideViewPort = true
        loginWebView.settings.javaScriptEnabled = true

        // Add JavaScript interface to capture credentials if user types them manually
        loginWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun saveCredentials(username: String, password: String) {
                lifecycleScope.launch {
                    // Use the correct F1Credentials constructor (assuming subToken isn't needed here)
                    // If your F1Credentials requires 3 args, adjust accordingly or update the data class
                    val credentials = F1Credentials(login = username, password = password)
                    credentialsService.saveCredentials(credentials)
                    Log.d(TAG, "Credentials saved via JS Interface for: $username")
                }
            }
        }, "Android")

        loginWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")

                // Check if we are currently on the login page
                if (url?.startsWith(F1_LOGIN_URL) == true) {
                    // We are on the login page. Attempt auto-fill.
                    Log.d(TAG, "Login page finished loading. Attempting auto-fill...")
                    autoFillCredentials() // This will use saved credentials
                    // Also setup capture in case auto-fill fails and user enters manually
                    captureCredentials()
                } else {
                    // We are on a *different* page (likely after a successful login)
                    // Now check for the session cookie to confirm login and get the token
                    Log.d(TAG, "Page loaded is not the login page. Checking for session cookie...")
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (credentialsService.storeToken(cookies)) {
                        // Login successful (manual or auto), token stored. Navigate home.
                        Log.d(TAG, "Session token found and stored. Navigating home.")
                        navigateToHome()
                    } else {
                        // Landed on a non-login page, but couldn't find/store token.
                        // This might indicate a problem or an unexpected page.
                        Log.w(TAG, "Not on login page, but failed to store token from cookies. Current URL: $url")
                        // Optional: You could force navigation back to login here if needed
                        // view?.loadUrl(F1_LOGIN_URL)
                    }
                }
            }
        }

        // Clear cookies specifically for the domain before loading to ensure a clean slate?
        // CookieManager.getInstance().removeSessionCookies(null) // Use carefully
        // CookieManager.getInstance().flush()
        Log.d(TAG, "Loading login URL: $F1_LOGIN_URL")
        loginWebView.loadUrl(F1_LOGIN_URL)
    }

    // Keep captureCredentials as is - useful if auto-login fails
    private fun captureCredentials() {
        val jsCode = """
        javascript:(function() {
            var loginButton = document.querySelector('button.btn.btn-primary[type="submit"]');
            // Check if the listener is already attached
            if (loginButton && !loginButton.hasAttribute('data-listener-attached')) {
                loginButton.setAttribute('data-listener-attached', 'true'); // Mark as attached
                loginButton.addEventListener('click', function() {
                    var loginInput = document.querySelector('.txtLogin');
                    var passwordInput = document.querySelector('.txtPassword');
                    var login = loginInput ? loginInput.value : null;
                    var password = passwordInput ? passwordInput.value : null;
                    if (login && password) {
                        console.log('CaptureCredentials: Sending credentials to Android.');
                        window.Android.saveCredentials(login, password);
                    } else {
                        console.log('CaptureCredentials: Login or Password field not found or empty.');
                    }
                });
                console.log('CaptureCredentials: Listener attached to login button.');
            } else if (loginButton) {
                console.log('CaptureCredentials: Listener already attached.');
            } else {
                console.log('CaptureCredentials: Login button not found.');
            }
        })();
        """
        loginWebView.evaluateJavascript(jsCode) { result ->
            Log.d(TAG, "CaptureCredentials JS execution result: $result")
        }
    }


    private fun autoFillCredentials() {
        lifecycleScope.launch {
            val settings = settingsRepository.getCurrent()
            val savedCredentials = credentialsService.getSavedCredentials()
            val login: String
            val password: String
            when {
                settings.f1Username.isNotEmpty() -> {
                    login = settings.f1Username
                    password = settings.f1Password
                    Log.d(TAG, "Using credentials from Settings for auto-fill.")
                }
                savedCredentials != null -> {
                    login = savedCredentials.login
                    password = savedCredentials.password
                    Log.d(TAG, "Saved credentials found for ${login}. Injecting JS for auto-fill.")
                }
                BuildConfig.F1_USERNAME.isNotEmpty() -> {
                    login = BuildConfig.F1_USERNAME
                    password = BuildConfig.F1_PASSWORD
                    Log.d(TAG, "No saved credentials. Using build-time fallback credentials.")
                    // Persist so subsequent launches use SharedPreferences (no need to re-read BuildConfig)
                    credentialsService.saveCredentials(
                        fr.groggy.racecontrol.tv.f1.F1Credentials(login = login, password = password)
                    )
                }
                else -> {
                    Log.d(TAG, "No saved credentials and no build-time credentials. Auto-fill skipped.")
                    return@launch
                }
            }
            // Always proceed with JS injection after resolving credentials above
            Log.d(TAG, "Injecting JS for auto-fill.")
                // Escape potential special characters in username/password for JS
                val escapedLogin = login.replace("'", "\\'")
                val escapedPassword = password.replace("'", "\\'")

                val jsCode = """
                javascript:(function() {
                    var loginField = document.querySelector('.txtLogin');
                    var passwordField = document.querySelector('.txtPassword');
                    var loginButton = document.querySelector('button.btn.btn-primary[type="submit"]');
                    var filled = false;

                    if (loginField) {
                        loginField.value = '$escapedLogin';
                        console.log('AutoFill: Login field filled.');
                        filled = true;
                    } else {
                        console.log('AutoFill: Login field (.txtLogin) not found.');
                    }

                    if (passwordField) {
                        passwordField.value = '$escapedPassword';
                        console.log('AutoFill: Password field filled.');
                        filled = filled && true; // Both must be filled
                    } else {
                        console.log('AutoFill: Password field (.txtPassword) not found.');
                        filled = false;
                    }

                    if (filled && loginButton) {
                        console.log('AutoFill: Credentials filled, attempting click after 1s.');
                        // Added small delay to ensure fields are processed if needed by page JS
                        setTimeout(function() {
                            console.log('AutoFill: Clicking login button.');
                            loginButton.click();
                        }, 1000); // 1 second delay
                    } else if (!filled) {
                       console.log('AutoFill: Not clicking button because fields were not filled.');
                    } else {
                       console.log('AutoFill: Login button not found, cannot click.');
                    }
                })();
                """
                loginWebView.evaluateJavascript(jsCode) { result ->
                    Log.d(TAG, "AutoFill JS execution result: $result")
                }
        }
    }

    private fun navigateToHome() {
        Log.d(TAG, "Navigating home (isSilentReAuth=$isSilentReAuth isTaskRoot=$isTaskRoot)")
        if (isSilentReAuth && !isTaskRoot) {
            // Something (e.g. HomeActivity) is behind us in the back stack — just go back
            finish()
        } else {
            startActivity(HomeActivity.intent(this))
            finish()
        }
    }
}
