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
import android.net.Uri
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class SignInActivity : ComponentActivity() {

    companion object {
        private val TAG = SignInActivity::class.simpleName
        private const val F1TV_URL = "https://f1tv.formula1.com/"
        private const val F1TV_HOST = "f1tv.formula1.com"
        // Only used when the "Sign in" link can't be found on the F1 TV homepage.
        // Redirect to F1 TV after login so a full page load happens once the login-session cookie is set
        private const val F1_LOGIN_FALLBACK_URL =
            "https://account.formula1.com/#/en/login?redirect=https%3A%2F%2Ff1tv.formula1.com%2F"
        private const val SIGN_IN_LINK_POLL_DELAY_MS = 1_000L
        private const val SIGN_IN_LINK_POLL_ATTEMPTS = 15
        private const val EXTRA_SILENT_REAUTH = "silent_reauth"

        /**
         * Clicks the "accept" button of whatever cookie consent dialog is shown, matched on its visible text.
         * F1's dialog lives in a cross-origin iframe (consent.formula1.com), and account.formula1.com only
         * redirects to the login page after consent, so this is also injected into every frame at document start.
         */
        private val COOKIE_CONSENT_JS = """
        (function() {
            if (window.__f1CookieConsentAutomationInstalled) {
                return 'already-installed';
            }
            window.__f1CookieConsentAutomationInstalled = true;

            var includeTerms = ['accept all', 'allow all', 'accept cookies', 'allow cookies', 'accept', 'agree', 'got it'];
            var excludeTerms = ['reject', 'decline', 'deny', 'manage', 'settings', 'preferences', 'learn more'];
            var observer = null;
            var intervalId = null;

            function isVisible(element) {
                return element.offsetParent !== null && !element.disabled;
            }

            function collectButtons(root, found) {
                try {
                    root.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"]').forEach(function(element) {
                        found.push(element);
                    });
                    root.querySelectorAll('*').forEach(function(element) {
                        if (element.shadowRoot) {
                            collectButtons(element.shadowRoot, found);
                        }
                    });
                } catch (error) {
                    console.log('CookieConsent: scan failed: ' + error);
                }
            }

            function labelOf(element) {
                return [element.innerText, element.value, element.getAttribute('aria-label')]
                    .filter(Boolean).join(' ').trim().toLowerCase();
            }

            function findAcceptButton() {
                var buttons = [];
                collectButtons(document, buttons);
                buttons = buttons.filter(isVisible).filter(function(element) {
                    var label = labelOf(element);
                    return label.length > 0 && label.length < 60
                        && !excludeTerms.some(function(term) { return label.indexOf(term) !== -1; });
                });
                // Prefer the most specific term, e.g. "Accept all" over a generic "Accept"
                for (var i = 0; i < includeTerms.length; i++) {
                    var match = buttons.find(function(element) { return labelOf(element).indexOf(includeTerms[i]) !== -1; });
                    if (match) {
                        return match;
                    }
                }
                return null;
            }

            function stop() {
                if (observer) {
                    observer.disconnect();
                }
                if (intervalId) {
                    clearInterval(intervalId);
                }
            }

            function clickConsentButton() {
                var candidate = findAcceptButton();
                if (!candidate) {
                    return false;
                }
                console.log('CookieConsent: clicking "' + labelOf(candidate) + '" in ' + location.host);
                stop();
                candidate.click();
                return true;
            }

            function start() {
                if (clickConsentButton()) {
                    return;
                }
                try {
                    observer = new MutationObserver(clickConsentButton);
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                } catch (error) {
                    console.log('CookieConsent: observer install failed: ' + error);
                }
                var attempts = 0;
                intervalId = setInterval(function() {
                    attempts += 1;
                    if (clickConsentButton() || attempts >= 30) {
                        stop();
                    }
                }, 1000);
            }

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', start);
                return 'deferred';
            }
            start();
            return 'started';
        })();
        """.trimIndent()

        /**
         * Locates the login form by its shape instead of F1-specific ids/classes, so it survives login page changes.
         * Only visible fields count: account.formula1.com keeps hidden forgot/reset-password forms in the DOM whose
         * submit button would otherwise get clicked.
         */
        private val FIND_LOGIN_FORM_JS = """
            function isVisible(element) {
                return !!element && element.offsetParent !== null && !element.disabled;
            }

            function visibleIn(root, selector) {
                return Array.prototype.slice.call(root.querySelectorAll(selector)).filter(isVisible);
            }

            function findLoginForm() {
                var passwords = visibleIn(document, 'input[type="password"]');
                var password = passwords.find(function(input) {
                    return input.autocomplete === 'current-password';
                }) || passwords.find(function(input) {
                    return input.autocomplete !== 'new-password';
                });
                if (!password) {
                    return {};
                }

                var root = password.closest('form') || document;
                var textInputs = visibleIn(root, 'input:not([type]), input[type="text"], input[type="email"]');
                var login = textInputs.find(function(input) {
                    return input.autocomplete === 'username' || input.autocomplete === 'email'
                        || input.type === 'email' || /user|email|login/i.test(input.name + ' ' + input.id);
                }) || textInputs[0];

                var buttons = visibleIn(root, 'button, input[type="submit"]');
                var submit = buttons.find(function(button) {
                    return /^(submit|form-submit)$/i.test(button.getAttribute('type') || '');
                }) || buttons.find(function(button) {
                    return /(sign|log)\s*in/i.test(button.innerText || button.value || '');
                });

                return { login: login, password: password, submit: submit };
            }
        """.trimIndent()
        private const val AUTO_FILL_RETRY_DELAY_MS = 2_000L
        private const val AUTO_FILL_RETRY_ATTEMPTS = 8
        private const val EXTRA_FRESH_LOGIN = "fresh_login"
        // A cookie token closer to expiry than this triggers a full login instead of being reused
        private const val MIN_TOKEN_REMAINING_VALIDITY_MS = 24L * 60 * 60 * 1000
        // Homepage + sign-in link + consent + login page + redirect back can take a while on a TV
        private const val SILENT_REAUTH_TIMEOUT_MS = 60_000L

        fun intent(context: Context) = Intent(context, SignInActivity::class.java)

        fun intentClearTask(context: Context) = intent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        /** For when F1 rejected the token: drops the WebView session first so the cookie token isn't reused. */
        fun intentFreshLogin(context: Context) = intentClearTask(context).apply {
            putExtra(EXTRA_FRESH_LOGIN, true)
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
    // Set once we've left the F1 TV homepage via its "Sign in" link; every page after that is part of the login flow
    private var loginFlowStarted = false
    // Set once the WebView cookies were cleared to force a full login; after that any unexpired token is accepted
    private var hasResetSession = false

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
            // Safety net: if auth doesn't complete in time, navigate home anyway
            lifecycleScope.launch {
                delay(SILENT_REAUTH_TIMEOUT_MS)
                if (!isFinishing) {
                    Log.w(TAG, "Silent re-auth timed out — proceeding to home")
                    navigateToHome()
                }
            }
        }

        setupWebView()
        if (intent.getBooleanExtra(EXTRA_FRESH_LOGIN, false)) {
            Log.d(TAG, "Fresh login requested: clearing WebView session first")
            restartWithFreshSession()
        } else {
            Log.d(TAG, "Loading F1 TV homepage: $F1TV_URL")
            loginWebView.loadUrl(F1TV_URL)
        }
    }

    private fun restartWithFreshSession() {
        hasResetSession = true
        loginFlowStarted = false
        pendingAutoFillRetry?.cancel()
        CookieManager.getInstance().removeAllCookies {
            if (!isFinishing) {
                Log.d(TAG, "WebView session cleared. Loading F1 TV homepage: $F1TV_URL")
                loginWebView.loadUrl(F1TV_URL)
            }
        }
    }

    private fun setupWebView() {
        Log.d(TAG, "Setting up WebView")
        loginWebView.settings.domStorageEnabled = true
        loginWebView.settings.useWideViewPort = true
        loginWebView.settings.javaScriptEnabled = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs in iframes too, unlike evaluateJavascript, so it can reach the cross-origin consent dialog
            WebViewCompat.addDocumentStartJavaScript(loginWebView, COOKIE_CONSENT_JS, setOf("*"))
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT not supported; cookie consent can only be accepted in the main frame")
        }

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
                val minRemainingValidityMs = if (hasResetSession) 0L else MIN_TOKEN_REMAINING_VALIDITY_MS
                when (credentialsService.storeToken(cookies, minRemainingValidityMs)) {
                    CredentialsService.CookieTokenResult.STORED -> {
                        Log.d(TAG, "Session token found and stored. Navigating home.")
                        navigateToHome()
                        return
                    }
                    CredentialsService.CookieTokenResult.STALE -> if (!hasResetSession) {
                        Log.d(TAG, "Cookie token is expired or about to expire. Forcing a full login.")
                        restartWithFreshSession()
                        return
                    }
                    CredentialsService.CookieTokenResult.MISSING -> Unit
                }

                if (!loginFlowStarted) {
                    // On the F1 TV homepage: follow its own "Sign in" link so we always use F1's current login URL
                    loginFlowStarted = true
                    lifecycleScope.launch { navigateToSignIn() }
                    return
                }

                // Check if we are currently on the login page
                if (isLoginPage(url)) {
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

    }

    private suspend fun navigateToSignIn() {
        val signInUrl = findSignInUrl()
        if (isFinishing) {
            return
        }
        if (signInUrl != null) {
            Log.d(TAG, "Found sign-in link on F1 TV homepage: $signInUrl")
            loginWebView.loadUrl(signInUrl)
        } else {
            Log.w(TAG, "Sign-in link not found on F1 TV homepage, using fallback: $F1_LOGIN_FALLBACK_URL")
            loginWebView.loadUrl(F1_LOGIN_FALLBACK_URL)
        }
    }

    /** The F1 TV homepage renders its "Sign in" link client-side, so poll until it shows up. */
    private suspend fun findSignInUrl(): String? {
        val jsCode = """
        (function() {
            var links = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
            var link = links.find(function(a) { return /\/login\b/i.test(a.href); })
                || links.find(function(a) { return /^\s*(sign|log)\s*in\s*$/i.test(a.innerText || ''); });
            return link ? link.href : null;
        })();
        """
        repeat(SIGN_IN_LINK_POLL_ATTEMPTS) {
            val result = evaluateJavascript(jsCode)
            if (result != null && result != "null") {
                // evaluateJavascript returns a JSON-encoded string
                return JSONTokener(result).nextValue() as? String
            }
            if (isFinishing) {
                return null
            }
            delay(SIGN_IN_LINK_POLL_DELAY_MS)
        }
        return null
    }

    private suspend fun evaluateJavascript(jsCode: String): String? = suspendCancellableCoroutine { continuation ->
        loginWebView.evaluateJavascript(jsCode) { result -> continuation.resume(result) }
    }

    private fun isLoginPage(url: String?): Boolean {
        if (!loginFlowStarted || url == null) {
            return false
        }
        return Uri.parse(url).host != F1TV_HOST
    }

    private fun autoAcceptCookieDialog() {
        loginWebView.evaluateJavascript(COOKIE_CONSENT_JS) { result ->
            Log.d(TAG, "Cookie consent JS execution result: $result")
        }
    }

    // Keep captureCredentials as is - useful if auto-login fails
    private fun captureCredentials() {
        val jsCode = """
        javascript:(function() {
            $FIND_LOGIN_FORM_JS

            var loginButton = findLoginForm().submit;
            // Check if the listener is already attached
            if (loginButton && !loginButton.hasAttribute('data-listener-attached')) {
                loginButton.setAttribute('data-listener-attached', 'true'); // Mark as attached
                loginButton.addEventListener('click', function() {
                    var form = findLoginForm();
                    var loginInput = form.login;
                    var passwordInput = form.password;
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
                    if (!isLoginPage(loginWebView.url)) {
                        return@launch
                    }
                    // No token check here: during a silent re-auth the old token is still valid.
                    // navigateToHome() cancels this loop once the new token is stored.
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

    private fun fillLoginForm(login: String, password: String, autoSubmit: Boolean, allowResubmit: Boolean = false) {
        Log.d(TAG, "Injecting JS for auto-fill autoSubmit=$autoSubmit allowResubmit=$allowResubmit")
        val escapedLogin = login
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        val escapedPassword = password
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        val shouldAutoSubmit = if (autoSubmit) "true" else "false"
        val allowResubmitJs = if (allowResubmit) "true" else "false"

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

            $FIND_LOGIN_FORM_JS

            var expectedLogin = '$escapedLogin';
            var expectedPassword = '$escapedPassword';

            function fillFields(form) {
                return setNativeValue(form.login, expectedLogin) && setNativeValue(form.password, expectedPassword);
            }

            // Login widgets can still be initialising (and clear or re-render their fields) right after the page
            // loads, so only click once the values have stuck and the button is enabled; otherwise refill and wait.
            function submitWhenSettled(attempt) {
                setTimeout(function() {
                    var form = findLoginForm();
                    var settled = form.login && form.password && form.submit
                        && form.login.value === expectedLogin
                        && form.password.value === expectedPassword
                        && form.submit.getAttribute('aria-disabled') !== 'true';
                    if (settled) {
                        console.log('AutoFill: Clicking login button. attempt=' + attempt);
                        window.__f1AutoFillSubmitted = true;
                        window.__f1AutoFillPending = false;
                        form.submit.click();
                        return;
                    }
                    if (attempt >= 20) {
                        console.log('AutoFill: form never settled, not submitting.');
                        window.__f1AutoFillPending = false;
                        return;
                    }
                    console.log('AutoFill: form not settled yet, refilling. attempt=' + attempt);
                    if (form.login && form.password) {
                        fillFields(form);
                    }
                    submitWhenSettled(attempt + 1);
                }, 1000);
            }

            function tryFill() {
                // Never auto-submit twice on the same page: if the password was wrong, retrying could lock the account
                if (!$allowResubmitJs && (window.__f1AutoFillSubmitted || window.__f1AutoFillPending)) {
                    return true;
                }
                var form = findLoginForm();
                if (!form.login || !form.password || !fillFields(form)) {
                    return false;
                }

                console.log('AutoFill: credentials filled.');
                if ($shouldAutoSubmit) {
                    window.__f1AutoFillPending = true;
                    submitWhenSettled(1);
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
                        fillLoginForm(login = email, password = password, autoSubmit = true, allowResubmit = true)
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
        // onPageFinished can report the token for several page loads in a row
        if (isFinishing) {
            return
        }
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
