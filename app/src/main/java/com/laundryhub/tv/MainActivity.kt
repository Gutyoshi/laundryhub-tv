package com.laundryhub.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var offlineView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    // Exit password mechanism
    private val exitPassword = "1234"
    private var backPressCount = 0
    private var firstBackPressTime = 0L
    private val backPressWindow = 3000L // 5 presses within 3 seconds
    private val backPressRequired = 5

    private val displayUrl: String
        get() = BuildConfig.DISPLAY_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fullscreen immersive
        hideSystemUI()

        // Layout
        val root = FrameLayout(this)

        // Offline message
        offlineView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            text = "Aguardando conexão..."
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1a1a2e.toInt())
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }

        // WebView (with crash protection)
        try {
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            setupWebView()
            root.addView(webView)
            root.addView(offlineView)

            setContentView(root)

            // Load or wait for connection
            if (isOnline()) {
                webView.loadUrl(displayUrl)
            } else {
                showOffline()
            }

            // Monitor connectivity changes
            registerNetworkCallback()
        } catch (e: Exception) {
            // WebView not available on this device - show error
            offlineView.text = "Erro: WebView não disponível.\nAtualize o Android System WebView na Play Store."
            offlineView.visibility = View.VISIBLE
            root.addView(offlineView)
            setContentView(root)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            // JavaScript & web features
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false  // Autoplay audio/video

            // Cache - stores assets locally
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)

            // Performance
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }

            // Allow mixed content (http resources on https page)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Viewport
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Hardware acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only handle main frame errors
                if (request?.isForMainFrame == true) {
                    showOffline()
                    scheduleRetry()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                showOnline()
                cancelRetry()
            }

            // Stay inside the app - don't open external browser
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }

    // ========================================
    // Fullscreen / Kiosk mode
    // ========================================

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    // ========================================
    // Key handling + exit password
    // ========================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBackPress()
                return true
            }
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH -> return true // Block
            else -> return super.onKeyDown(keyCode, event)
        }
    }

    override fun onBackPressed() {
        // Blocked - handled in onKeyDown
    }

    private fun handleBackPress() {
        val now = System.currentTimeMillis()

        if (now - firstBackPressTime > backPressWindow) {
            // Reset counter
            backPressCount = 1
            firstBackPressTime = now
        } else {
            backPressCount++
        }

        if (backPressCount >= backPressRequired) {
            backPressCount = 0
            showPasswordDialog()
        }
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = "Senha"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Sair do modo display")
            .setMessage("Digite a senha para sair:")
            .setView(input)
            .setPositiveButton("Sair") { _, _ ->
                if (input.text.toString() == exitPassword) {
                    exitKiosk()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exitKiosk() {
        // Stop watchdog service
        val serviceIntent = Intent(this, WatchdogService::class.java)
        stopService(serviceIntent)

        // Save flag so watchdog doesn't restart
        getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("exit_requested", true)
            .apply()

        // Close app
        finishAffinity()
    }

    // ========================================
    // Connectivity handling
    // ========================================

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    webView.loadUrl(displayUrl)
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    showOffline()
                    scheduleRetry()
                }
            }
        })
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
    }

    private fun showOnline() {
        offlineView.visibility = View.GONE
    }

    private fun scheduleRetry() {
        cancelRetry()
        retryRunnable = Runnable {
            if (isOnline()) {
                webView.loadUrl(displayUrl)
            } else {
                scheduleRetry()
            }
        }
        handler.postDelayed(retryRunnable!!, 10_000) // Retry every 10s
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    // ========================================
    // Lifecycle
    // ========================================

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemUI()

        // Clear exit flag when app is opened again
        getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("exit_requested", false)
            .apply()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        cancelRetry()
        super.onDestroy()
    }
}
