package fr.groggy.racecontrol.tv.ui.signin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.ui.home.HomeActivity
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

        loginWebView.settings.domStorageEnabled = true
        loginWebView.settings.useWideViewPort = true
        loginWebView.settings.javaScriptEnabled = true
        loginWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (credentialsService.storeToken(CookieManager.getInstance().getCookie(url))) {
                    startActivity(HomeActivity.intent(this@SignInActivity))
                    finish()
                }
            }
        }
        loginWebView.loadUrl("https://account.formula1.com/#/en/login")
    }

}
