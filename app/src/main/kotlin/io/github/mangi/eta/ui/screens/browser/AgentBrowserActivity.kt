package io.github.mangi.eta.ui.screens.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserInteractionPolicy
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** WebView 保持在固定窗口内，进出动画由系统合成窗口，避免整页绘制裁剪介入网页渲染。 */
class AgentBrowserActivity : ComponentActivity() {
    private var interactionController: BrowserInteractionController? = null
    private var browserSession: AgentBrowserSession? = null
    private val initialNavigation = MutableStateFlow<Pair<Long, String>?>(null)
    private var navigationId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY)?.takeIf { it.isNotBlank() }
        if (sessionKey == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val session = BrowserSessionRegistry.get(sessionKey)
        browserSession = session
        interactionController = BrowserInteractionController(this, session).also(session::bindInteractionHost)
        if (savedInstanceState == null) enqueueInitialNavigation(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initialAppearance)
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = { isDark ->
                        val style = if (isDark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    },
                ) {
                    AgentBrowserScreen(
                        session = session,
                        onBack = ::finish,
                        onScanQr = { interactionController?.showQrOptions() },
                    )
                    val requestedNavigation by initialNavigation.collectAsState()
                    LaunchedEffect(requestedNavigation) {
                        requestedNavigation?.let { request ->
                            val (_, url) = request
                            // Consume the launch payload before navigating, so recreation cannot replay it over a form.
                            intent.removeExtra(EXTRA_INITIAL_URL)
                            try {
                                // AndroidView has attached the existing page before this effect runs.
                                val result = withContext(Dispatchers.IO) { session.navigateFromUser(applicationContext, url) }
                                if (!JSONObject(result.content).optBoolean("ok", false)) {
                                    Toast.makeText(this@AgentBrowserActivity, R.string.browser_user_action_failed, Toast.LENGTH_SHORT).show()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                AndroidAgentLogger.warn("Browser initial navigation failed: type=${failure.safeLogType()}")
                                Toast.makeText(this@AgentBrowserActivity, R.string.browser_user_action_failed, Toast.LENGTH_SHORT).show()
                            } finally {
                                initialNavigation.compareAndSet(request, null)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_SESSION_KEY) != this.intent.getStringExtra(EXTRA_SESSION_KEY)) {
            startActivity(Intent(intent).apply {
                flags = flags and Intent.FLAG_ACTIVITY_SINGLE_TOP.inv() and Intent.FLAG_ACTIVITY_CLEAR_TOP.inv()
            })
            finish()
            return
        }
        setIntent(intent)
        enqueueInitialNavigation(intent)
    }

    private fun enqueueInitialNavigation(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_INITIAL_URL)?.let(BrowserInteractionPolicy::confirmedQrWebAddress) ?: return
        initialNavigation.value = ++navigationId to url
    }

    override fun onStart() {
        interactionController?.setForeground(true)
        super.onStart()
    }

    override fun onStop() {
        interactionController?.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        interactionController?.let { controller ->
            controller.cancelPageRequests()
            browserSession?.unbindInteractionHost(controller)
        }
        interactionController = null
        browserSession = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION_KEY = "io.github.mangi.eta.extra.BROWSER_SESSION_KEY"
        private const val EXTRA_INITIAL_URL = "io.github.mangi.eta.extra.BROWSER_INITIAL_URL"

        fun createIntent(context: Context, sessionKey: String, initialUrl: String? = null): Intent {
            require(sessionKey.isNotBlank())
            return Intent(context, AgentBrowserActivity::class.java).putExtra(EXTRA_SESSION_KEY, sessionKey).apply {
                initialUrl?.let(BrowserInteractionPolicy::confirmedQrWebAddress)?.let { putExtra(EXTRA_INITIAL_URL, it) }
            }
        }
    }
}
