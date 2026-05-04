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
import android.util.Log
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

    companion object {
        @Volatile
        var isInForeground: Boolean = false
    }

    private var webView: WebView? = null
    private lateinit var offlineView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    // Exit password
    private val exitPassword = "1234"
    private var backPressCount = 0
    private var firstBackPressTime = 0L
    private val backPressWindow = 3000L
    private val backPressRequired = 5

    private val displayUrl: String
        get() = BuildConfig.DISPLAY_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fullscreen
        hideSystemUI()

        // Layout
        val root = FrameLayout(this)

        // Offline/error message
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

        try {
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }
            setupWebView()
            root.addView(webView)
            root.addView(offlineView)
            setContentView(root)

            // Always show offline view first; switch to WebView only after URL loads
            showOffline()
            registerNetworkCallback()

            // Try load if validated; otherwise NetworkCallback or retry will trigger
            if (isInternetValidated()) {
                tryLoadUrl()
            } else {
                scheduleRetry(3_000L)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "WebView init failed", e)
            offlineView.text = "Erro: WebView não disponível.\nAtualize o Android System WebView."
            offlineView.visibility = View.VISIBLE
            root.addView(offlineView)
            setContentView(root)
        }

    // Battery optimization - silent, no popup
    silentBatteryExemption()
}

private fun silentBatteryExemption() {
    // No popup - TV doesn't have battery anyway
  }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false

            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)

            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView?.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w("MainActivity", "Main frame error: ${error?.errorCode} ${error?.description}")
                    // Hide WebView so Chromium error page never shows
                    view?.loadUrl("about:blank")
                    showOffline()
                    scheduleRetry(3_000L)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url != "about:blank" && url.startsWith("http")) {
                    showOnline()
                    cancelRetry()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false
        }

        webView?.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }

    // ========================================
    // Fullscreen
    // ========================================

    private fun hideSystemUI() {
        // Try modern API first, fallback to legacy if it fails
        // (some custom Android builds return null insetsController)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Modern hideSystemUI failed, using fallback", e)
        }

        // Legacy fallback - works on all Android versions
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        } catch (e: Exception) {
            Log.e("MainActivity", "Legacy hideSystemUI failed", e)
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
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBackPress()
                true
            }
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { }

    private fun handleBackPress() {
        val now = System.currentTimeMillis()
        if (now - firstBackPressTime > backPressWindow) {
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
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sair do modo display")
            .setMessage("Digite a senha:")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == exitPassword) {
                    showExitOptionsDialog()
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()

        // Enter key confirms automatically
        input.setOnEditorActionListener { _, _, _ ->
            if (input.text.toString() == exitPassword) {
                dialog.dismiss()
                showExitOptionsDialog()
            }
            true
        }

        dialog.show()
        input.requestFocus()
    }

    private fun showExitOptionsDialog() {
        val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        val launcherOn = prefs.getBoolean("launcher_enabled", false)
        val a11yActive = isAccessibilityServiceEnabled()
        val status = when {
            launcherOn && a11yActive -> "ATIVADO"
            launcherOn && !a11yActive -> "ATIVADO (requer configuração)"
            else -> "DESATIVADO"
        }

        val options = arrayOf(
            "Iniciar com a TV: $status  (tocar para alternar)",
            "Sair do app (definitivo)",
            "Cancelar"
        )

        AlertDialog.Builder(this)
            .setTitle("Modo Kiosk")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleLauncherMode(launcherOn)
                    1 -> exitPermanently()
                    2 -> { /* cancel */ }
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun exitPermanently() {
        getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("exit_requested", true)
            .putLong("paused_until", 0L)
            .apply()
        finishAffinity()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains("${packageName}/.KioskAccessibilityService") ||
                enabledServices.contains("$packageName/com.laundryhub.tv.KioskAccessibilityService")
        } catch (e: Exception) {
            false
        }
    }

private fun toggleLauncherMode(currentlyEnabled: Boolean) {
    val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    if (currentlyEnabled) {
      // DISABLE: just flip flag
      prefs.edit().putBoolean("launcher_enabled", false).apply()

      AlertDialog.Builder(this)
      .setTitle("Iniciar automático DESATIVADO")
      .setMessage(
      "O app não vai mais abrir quando ligar a TV.\n\n" +
      "A TV vai voltar ao normal — só o launcher original aparece."
      )
      .setPositiveButton("OK", null)
      .show()
    } else {
      // ENABLE: set flag and open accessibility settings for user to enable
      prefs.edit().putBoolean("launcher_enabled", true).apply()

      AlertDialog.Builder(this)
      .setTitle("Ativar Modo Kiosk")
      .setMessage(
      "Para o app abrir automaticamente quando a TV ligar:\n\n" +
      "1. Encontre 'LaundryHub Kiosk' na lista\n" +
      "2. Ative a chave\n" +
      "3. Confirme 'Permitir'\n" +
      "4. Volte para o app\n\n" +
      "Isso impede que saiam do display acidentalmente."
      )
      .setPositiveButton("Abrir configurações") { _, _ ->
        openAccessibilitySettings()
      }
      .setNegativeButton("Depois", null)
      .show()
    }
  }

    /**
     * Try multiple intent paths to open accessibility settings.
     * Some TV firmwares (Mi Stick, HQ AOSP) don't expose ACTION_ACCESSIBILITY_SETTINGS
     * directly, so fall back to generic settings or app details.
     */
    private fun openAccessibilitySettings() {
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent("com.android.settings.ACCESSIBILITY_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
        )

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Intent failed: ${intent.action}", e)
            }
        }

        // No settings activity worked - show manual instructions
        AlertDialog.Builder(this)
            .setTitle("Configuração manual necessária")
            .setMessage(
                "Esta TV não permite abrir as configurações automaticamente.\n\n" +
                "Faça manualmente:\n\n" +
                "1. Saia do app (apertar HOME no controle)\n" +
                "2. Vá em Configurações da TV\n" +
                "3. Procure 'Acessibilidade' (Accessibility)\n" +
                "4. Encontre 'LaundryHub Kiosk' na lista\n" +
                "5. Ative a chave\n" +
                "6. Confirme 'Permitir'\n" +
                "7. Volte para o app"
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    // ========================================
    // Connectivity
    // ========================================

    private fun isInternetValidated(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    private fun tryLoadUrl() {
        try {
            webView?.loadUrl(displayUrl)
        } catch (e: Exception) {
            Log.e("MainActivity", "loadUrl failed", e)
            scheduleRetry(5_000L)
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities
                ) {
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (validated) {
                        handler.post {
                            cancelRetry()
                            tryLoadUrl()
                        }
                    }
                }
                override fun onLost(network: Network) {
                    handler.post {
                        showOffline()
                        scheduleRetry(3_000L)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Network callback failed", e)
        }
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
        webView?.visibility = View.GONE
    }

    private fun showOnline() {
        offlineView.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun scheduleRetry(delayMs: Long = 5_000L) {
        cancelRetry()
        retryRunnable = Runnable {
            if (isInternetValidated()) {
                tryLoadUrl()
            } else {
                // Backoff: cap at 30s
                val nextDelay = minOf(delayMs * 2, 30_000L)
                scheduleRetry(nextDelay)
            }
        }
        handler.postDelayed(retryRunnable!!, delayMs)
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
    isInForeground = true
    webView?.onResume()
    hideSystemUI()
    getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    .edit().putBoolean("exit_requested", false).apply()

    // If offline view is showing, retry now (handles wake from sleep)
    if (offlineView.visibility == View.VISIBLE && isInternetValidated()) {
      tryLoadUrl()
    }

    // Check if user enabled accessibility service after being prompted
    val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    if (prefs.getBoolean("launcher_enabled", false) && isAccessibilityServiceEnabled()) {
      // User successfully enabled it - show confirmation
      // (optional - can be removed to avoid annoying popup)
    }
  }

    override fun onPause() {
        isInForeground = false
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.destroy()
        cancelRetry()
        super.onDestroy()
    }
}
