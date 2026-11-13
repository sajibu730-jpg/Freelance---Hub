package com.freelancehub.owner

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.content.Intent
import android.net.Uri

class OwnerMainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private val dashboardUrl = "https://sajibu730-jpg.github.io/Freelance---Hub/"
    private val dashboardHost = "sajibu730-jpg.github.io"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 27, 46)
        window.navigationBarColor = Color.rgb(7, 27, 46)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 27, 46))
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == dashboardHost) return false
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        return true
                    }
                    return true
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    Toast.makeText(this@OwnerMainActivity, "Secure connection failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@OwnerMainActivity.progress.progress = newProgress
                    this@OwnerMainActivity.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(32, 143, 224))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(225, 235, 245))
        }
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP
        ))
        setContentView(root)

        if (savedInstanceState == null) webView.loadUrl(dashboardUrl)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
