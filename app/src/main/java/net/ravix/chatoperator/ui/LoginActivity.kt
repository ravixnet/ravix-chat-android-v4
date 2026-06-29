package net.ravix.chatoperator.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.ravix.chatoperator.BuildConfig
import net.ravix.chatoperator.data.SecureSessionStore
import net.ravix.chatoperator.databinding.ActivityLoginBinding
import net.ravix.chatoperator.network.ChatApiClient

class LoginActivity : ComponentActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var store: SecureSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        store = SecureSessionStore(this)

        binding.serverUrl.setText(store.baseUrl.ifBlank { BuildConfig.DEFAULT_SERVER_URL })
        binding.username.setText(store.username)
        binding.loginButton.setOnClickListener { login() }
        binding.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                login()
                true
            } else {
                false
            }
        }
    }

    private fun login() {
        val server = binding.serverUrl.text?.toString().orEmpty()
        val username = binding.username.text?.toString().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            binding.message.text = "آدرس سرور، نام کاربری و رمز عبور را کامل وارد کنید."
            return
        }
        setLoading(true)
        binding.message.text = ""

        lifecycleScope.launch {
            runCatching { ChatApiClient().login(server, username, password) }
                .onSuccess { result ->
                    store.baseUrl = server
                    store.username = username
                    store.authToken = result.token
                    store.authCookie = result.cookie
                    store.socketUrl = result.socketUrl
                    store.socketPath = result.socketPath
                    store.keepOnline = true
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
                .onFailure { error ->
                    binding.message.text = error.message ?: "ورود انجام نشد."
                    setLoading(false)
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginButton.isEnabled = !loading
        binding.serverUrl.isEnabled = !loading
        binding.username.isEnabled = !loading
        binding.password.isEnabled = !loading
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loginButton.text = if (loading) "در حال اتصال…" else "ورود و اتصال"
    }

    private fun applyInsets() {
        val initialStart = binding.root.paddingStart
        val initialTop = binding.root.paddingTop
        val initialEnd = binding.root.paddingEnd
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialStart + bars.left,
                top = initialTop + bars.top,
                right = initialEnd + bars.right,
                bottom = initialBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
}
