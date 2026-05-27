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

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1.F1Credentials // Ensure this is imported
import fr.groggy.racecontrol.tv.ui.home.HomeActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SignInActivity : ComponentActivity() {

    companion object {
        private val TAG = SignInActivity::class.simpleName
        private const val F1_LOGIN_URL = "https://account.formula1.com/#/en/login"
        private const val EXTRA_SILENT_REAUTH = "silent_reauth"
        private const val AUTO_FILL_RETRY_DELAY_MS = 2_000L
        private const val AUTO_FILL_RETRY_ATTEMPTS = 8

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
    private var hasShownInitialSetupPrompt = false
    private var pendingAutoFillRetry: Job? = null

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
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                autoAcceptCookieDialog()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")
                autoAcceptCookieDialog()

                val cookies = CookieManager.getInstance().getCookie(url)
                if (credentialsService.storeToken(cookies)) {
                    Log.d(TAG, "Session token found and stored. Navigating home.")
                    navigateToHome()
                    return
                }

                // Check if we are currently on the login page
                if (url?.startsWith(F1_LOGIN_URL) == true) {
                    // We are on the login page. Attempt auto-fill.
                    Log.d(TAG, "Login page finished loading. Attempting auto-fill...")
                    // Also setup capture in case auto-fill fails and user enters manually
                    captureCredentials()
                    autoFillCredentials() // This will use saved credentials
                } else {
                    // Landed on a non-login page, but couldn't find/store token.
                    // This might indicate a problem or an unexpected page.
                    Log.w(TAG, "Not on login page, but failed to store token from cookies. Current URL: $url")
                }
            }
        }

        // Clear cookies specifically for the domain before loading to ensure a clean slate?
        // CookieManager.getInstance().removeSessionCookies(null) // Use carefully
        // CookieManager.getInstance().flush()
        Log.d(TAG, "Loading login URL: $F1_LOGIN_URL")
        loginWebView.loadUrl(F1_LOGIN_URL)
    }

    private fun autoAcceptCookieDialog() {
        val jsCode = """
        javascript:(function() {
            if (window.__f1CookieConsentAutomationInstalled) {
                window.__f1CookieConsentKick = Date.now();
            }

            function collectInteractiveElements(root, found) {
                if (!root) {
                    return;
                }

                var selectors = [
                    'button',
                    '[role="button"]',
                    'input[type="button"]',
                    'input[type="submit"]',
                    'a[role="button"]'
                ];

                selectors.forEach(function(selector) {
                    try {
                        root.querySelectorAll(selector).forEach(function(element) {
                            if (found.indexOf(element) === -1) {
                                found.push(element);
                            }
                        });
                    } catch (error) {
                        console.log('CookieConsent: selector scan failed for ' + selector + ': ' + error);
                    }
                });

                try {
                    root.querySelectorAll('*').forEach(function(element) {
                        if (element.shadowRoot) {
                            collectInteractiveElements(element.shadowRoot, found);
                        }
                    });
                } catch (error) {
                    console.log('CookieConsent: shadow DOM scan failed: ' + error);
                }

                try {
                    root.querySelectorAll('iframe').forEach(function(frame) {
                        try {
                            if (frame.contentDocument) {
                                collectInteractiveElements(frame.contentDocument, found);
                            }
                        } catch (error) {
                            console.log('CookieConsent: iframe scan skipped: ' + error);
                        }
                    });
                } catch (error) {
                    console.log('CookieConsent: iframe enumeration failed: ' + error);
                }
            }

            function looksLikeAcceptAction(element) {
                if (!element) {
                    return false;
                }

                var text = [
                    element.innerText,
                    element.textContent,
                    element.value,
                    element.getAttribute('aria-label'),
                    element.getAttribute('data-testid'),
                    element.getAttribute('title'),
                    element.id,
                    element.className
                ].filter(Boolean).join(' ').toLowerCase();

                var includeTerms = [
                    'accept',
                    'agree',
                    'allow all',
                    'accept all',
                    'accept cookies',
                    'allow cookies',
                    'consent',
                    'got it',
                    'ok'
                ];
                var excludeTerms = [
                    'reject',
                    'decline',
                    'deny',
                    'manage',
                    'settings',
                    'preferences',
                    'learn more'
                ];

                return includeTerms.some(function(term) { return text.indexOf(term) !== -1; }) &&
                    !excludeTerms.some(function(term) { return text.indexOf(term) !== -1; });
            }

            function clickConsentButton() {
                var elements = [];
                collectInteractiveElements(document, elements);
                var candidate = elements.find(looksLikeAcceptAction);

                if (!candidate) {
                    console.log('CookieConsent: no matching consent button found.');
                    return false;
                }

                console.log('CookieConsent: clicking consent button.');
                ['pointerdown', 'mousedown', 'mouseup', 'click'].forEach(function(eventName) {
                    try {
                        candidate.dispatchEvent(new MouseEvent(eventName, {
                            bubbles: true,
                            cancelable: true,
                            view: window
                        }));
                    } catch (error) {
                        console.log('CookieConsent: dispatch failed for ' + eventName + ': ' + error);
                    }
                });
                try {
                    candidate.click();
                } catch (error) {
                    console.log('CookieConsent: direct click failed: ' + error);
                }
                return true;
            }

            function scheduleRetryLoop() {
                var attempts = 0;
                var intervalId = setInterval(function() {
                    attempts += 1;
                    if (clickConsentButton() || attempts >= 20) {
                        clearInterval(intervalId);
                    }
                }, 1000);
            }

            if (!window.__f1CookieConsentAutomationInstalled) {
                window.__f1CookieConsentAutomationInstalled = true;
                try {
                    var observer = new MutationObserver(function() {
                        clickConsentButton();
                    });
                    observer.observe(document.documentElement || document.body, {
                        childList: true,
                        subtree: true,
                        attributes: true
                    });
                } catch (error) {
                    console.log('CookieConsent: observer install failed: ' + error);
                }
            }

            if (clickConsentButton()) {
                return 'clicked-now';
            }

            scheduleRetryLoop();

            return 'observer-installed';
        })();
        """
        loginWebView.evaluateJavascript(jsCode) { result ->
            Log.d(TAG, "Cookie consent JS execution result: $result")
        }
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
            val resolvedCredentials = resolveStoredCredentials()
            if (resolvedCredentials == null) {
                Log.d(TAG, "No saved credentials and no build-time credentials. Showing initial setup prompt.")
                maybeShowInitialSetupPrompt()
                return@launch
            }

            fillLoginForm(
                login = resolvedCredentials.login,
                password = resolvedCredentials.password,
                autoSubmit = true
            )

            pendingAutoFillRetry?.cancel()
            pendingAutoFillRetry = lifecycleScope.launch {
                repeat(AUTO_FILL_RETRY_ATTEMPTS) { attempt ->
                    delay(AUTO_FILL_RETRY_DELAY_MS)
                    if (isFinishing) {
                        return@launch
                    }
                    if (!loginWebView.url.orEmpty().startsWith(F1_LOGIN_URL)) {
                        return@launch
                    }
                    if (credentialsService.hasValidF1Credentials()) {
                        return@launch
                    }
                    Log.d(TAG, "Retrying auto-fill after consent popup / login page settle. attempt=${attempt + 1}")
                    fillLoginForm(
                        login = resolvedCredentials.login,
                        password = resolvedCredentials.password,
                        autoSubmit = true
                    )
                }
            }
        }
    }

    private suspend fun resolveStoredCredentials(): F1Credentials? {
        val settings = settingsRepository.getCurrent()
        if (settings.f1Username.isNotEmpty() && settings.f1Password.isNotEmpty()) {
            Log.d(TAG, "Using credentials from Settings for auto-fill.")
            return F1Credentials(login = settings.f1Username, password = settings.f1Password)
        }

        val savedCredentials = credentialsService.getSavedCredentials()
        if (savedCredentials != null) {
            Log.d(TAG, "Saved credentials found for ${savedCredentials.login}. Injecting JS for auto-fill.")
            return savedCredentials
        }

        return if (BuildConfig.F1_USERNAME.isNotEmpty() && BuildConfig.F1_PASSWORD.isNotEmpty()) {
            Log.d(TAG, "No saved credentials. Using build-time fallback credentials for this build.")
            F1Credentials(login = BuildConfig.F1_USERNAME, password = BuildConfig.F1_PASSWORD)
        } else {
            null
        }
    }

    private fun fillLoginForm(login: String, password: String, autoSubmit: Boolean) {
        Log.d(TAG, "Injecting JS for auto-fill autoSubmit=$autoSubmit")
        val escapedLogin = login
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        val escapedPassword = password
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        val shouldAutoSubmit = if (autoSubmit) "true" else "false"

        val jsCode = """
        javascript:(function() {
            function setNativeValue(element, value) {
                if (!element) {
                    return false;
                }

                var prototype = element.tagName === 'TEXTAREA'
                    ? window.HTMLTextAreaElement.prototype
                    : window.HTMLInputElement.prototype;
                var descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                if (descriptor && descriptor.set) {
                    descriptor.set.call(element, value);
                } else {
                    element.value = value;
                }

                ['input', 'change', 'blur'].forEach(function(eventName) {
                    element.dispatchEvent(new Event(eventName, { bubbles: true }));
                });
                return true;
            }

            function findLoginField() {
                return document.querySelector('.txtLogin')
                    || document.querySelector('input[type="email"]')
                    || document.querySelector('input[name="email"]');
            }

            function findPasswordField() {
                return document.querySelector('.txtPassword')
                    || document.querySelector('input[type="password"]');
            }

            function findSubmitButton() {
                return document.querySelector('button.btn.btn-primary[type="submit"]')
                    || document.querySelector('button[type="submit"]');
            }

            function tryFill() {
                var loginField = findLoginField();
                var passwordField = findPasswordField();
                var loginButton = findSubmitButton();
                var loginFilled = setNativeValue(loginField, '$escapedLogin');
                var passwordFilled = setNativeValue(passwordField, '$escapedPassword');

                if (!loginFilled || !passwordFilled) {
                    return false;
                }

                console.log('AutoFill: credentials filled.');
                if ($shouldAutoSubmit && loginButton) {
                    setTimeout(function() {
                        console.log('AutoFill: Clicking login button.');
                        loginButton.click();
                    }, 500);
                }
                return true;
            }

            if (tryFill()) {
                return 'filled-now';
            }

            var attempts = 0;
            var intervalId = setInterval(function() {
                attempts += 1;
                if (tryFill() || attempts >= 15) {
                    clearInterval(intervalId);
                }
            }, 1000);

            return 'scheduled-retries';
        })();
        """

        loginWebView.evaluateJavascript(jsCode) { result ->
            Log.d(TAG, "AutoFill JS execution result: $result")
        }
    }

    private fun maybeShowInitialSetupPrompt() {
        if (isSilentReAuth || hasShownInitialSetupPrompt) {
            return
        }
        hasShownInitialSetupPrompt = true

        val settings = settingsRepository.getCurrent()
        val density = resources.displayMetrics.density
        val fieldLayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (24 * density).toInt()
            val verticalPadding = (20 * density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        val emailInput = EditText(this).apply {
            hint = getString(R.string.signin_setup_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(settings.f1Username)
            layoutParams = fieldLayoutParams
        }
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.signin_setup_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(settings.f1Password)
            layoutParams = fieldLayoutParams
        }
        val audioUrlInput = EditText(this).apply {
            hint = getString(R.string.signin_setup_audio_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(settings.customRadioUrl)
            layoutParams = fieldLayoutParams
        }
        contentView.addView(emailInput)
        contentView.addView(passwordInput)
        contentView.addView(audioUrlInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.signin_setup_title)
            .setMessage(R.string.signin_setup_message)
            .setView(contentView)
            .setPositiveButton(R.string.signin_setup_confirm, null)
            .setNegativeButton(R.string.signin_setup_skip, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val skipButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            emailInput.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_NEXT || isConfirmKey(event)) {
                    passwordInput.requestFocus()
                    true
                } else {
                    false
                }
            }
            passwordInput.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_NEXT || isConfirmKey(event)) {
                    audioUrlInput.requestFocus()
                    true
                } else {
                    false
                }
            }
            audioUrlInput.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || isConfirmKey(event)) {
                    hideKeyboard(audioUrlInput)
                    saveButton.requestFocus()
                    true
                } else {
                    false
                }
            }

            val onDialogButtonFocused: (View, Boolean) -> Unit = { view, hasFocus ->
                if (hasFocus) {
                    hideKeyboard(currentFocus ?: view)
                }
            }
            saveButton.onFocusChangeListener = View.OnFocusChangeListener(onDialogButtonFocused)
            skipButton.onFocusChangeListener = View.OnFocusChangeListener(onDialogButtonFocused)
            saveButton.requestFocus()

            saveButton.setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                val audioUrl = audioUrlInput.text.toString().trim()

                if (email.isBlank() xor password.isBlank()) {
                    Toast.makeText(
                        this,
                        R.string.signin_setup_credentials_validation,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    settingsRepository.updateSignInDefaults(
                        username = email,
                        password = password,
                        customRadioUrl = audioUrl
                    )
                    if (email.isNotBlank() && password.isNotBlank()) {
                        credentialsService.saveCredentials(F1Credentials(login = email, password = password))
                        fillLoginForm(login = email, password = password, autoSubmit = true)
                    }
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun isConfirmKey(event: KeyEvent?): Boolean {
        return event?.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
    }

    private fun navigateToHome() {
        pendingAutoFillRetry?.cancel()
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
