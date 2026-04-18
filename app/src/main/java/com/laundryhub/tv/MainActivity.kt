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
            }
            setupWebView()
            root.addView(webView)
            root.addView(offlineView)
            setContentView(root)

            if (isOnline()) {
                webView?.loadUrl(displayUrl)
            } else {
                showOffline()
            }

            registerNetworkCallback()
        } catch (e: Exception) {
            Log.e("MainActivity", "WebView init failed", e)
            offlineView.text = "Erro: WebView não disponível.\nAtualize o Android System WebView."
            offlineView.visibility = View.VISIBLE
            root.addView(offlineView)
            setContentView(root)
        }

        // Battery optimization - silent, no popup
        silentBatteryExemption()

        // Self-heal: if launcher mode is enabled but accessibility isn't, force enable it
        ensureAccessibilityEnabled()
    }

    private fun silentBatteryExemption() {
        // No popup - TV doesn't have battery anyway
    }

    /**
     * Auto-heals the accessibility service activation.
     * Requires WRITE_SECURE_SETTINGS permission granted via ADB:
     * adb shell pm grant com.laundryhub.tv android.permission.WRITE_SECURE_SETTINGS
     */
    private fun ensureAccessibilityEnabled() {
        try {
            val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("launcher_enabled", false)) return

            val serviceName = "$packageName/.KioskAccessibilityService"
            val fullServiceName = "$packageName/com.laundryhub.tv.KioskAccessibilityService"
            val current = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (current.contains(serviceName) || current.contains(fullServiceName)) {
                Log.d("MainActivity", "Accessibility already enabled")
                return
            }

            // Not enabled - try to enable via WRITE_SECURE_SETTINGS
            val newValue = if (current.isEmpty()) fullServiceName else "$current:$fullServiceName"
            android.provider.Settings.Secure.putString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newValue
            )
            android.provider.Settings.Secure.putInt(
                contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.d("MainActivity", "Accessibility service auto-enabled")
        } catch (e: SecurityException) {
            Log.w("MainActivity", "WRITE_SECURE_SETTINGS not granted - auto-enable disabled")
        } catch (e: Exception) {
            Log.e("MainActivity", "Accessibility auto-enable failed", e)
        }
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
                    showOffline()
                    scheduleRetry()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                showOnline()
                cancelRetry()
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
            // DISABLE: just flip flag, accessibility service stops forcing
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
            // ENABLE: set flag + check if accessibility service is active
            prefs.edit().putBoolean("launcher_enabled", true).apply()

            if (isAccessibilityServiceEnabled()) {
                // Already configured
                AlertDialog.Builder(this)
                    .setTitle("Iniciar automático ATIVADO")
                    .setMessage(
                        "Pronto! O app vai abrir automaticamente quando ligar a TV."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                // Needs to enable accessibility service
                AlertDialog.Builder(this)
                    .setTitle("Configuração necessária")
                    .setMessage(
                        "Para o app abrir automaticamente quando a TV ligar, " +
                        "habilite o serviço de acessibilidade:\n\n" +
                        "1. Toque em 'Abrir configurações'\n" +
                        "2. Encontre 'LaundryHub Kiosk' na lista\n" +
                        "3. Ative o serviço\n" +
                        "4. Volte ao app"
                    )
                    .setPositiveButton("Abrir configurações") { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Accessibility settings not available", e)
                        }
                    }
                    .setNegativeButton("Depois", null)
                    .show()
            }
        }
    }

    // ========================================
    // Connectivity
    // ========================================

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.post { webView?.loadUrl(displayUrl) }
                }
                override fun onLost(network: Network) {
                    handler.post {
                        showOffline()
                        scheduleRetry()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Network callback failed", e)
        }
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
                webView?.loadUrl(displayUrl)
            } else {
                scheduleRetry()
            }
        }
        handler.postDelayed(retryRunnable!!, 10_000)
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
