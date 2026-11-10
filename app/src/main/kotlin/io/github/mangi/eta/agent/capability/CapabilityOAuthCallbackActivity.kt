package io.github.mangi.eta.agent.capability

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.core.NexusUiContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives the OAuth redirect without rendering callbacks or credentials. */
class CapabilityOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ApiOAuth2Client(applicationContext).completeAuthorization(intent?.data)
            }
            startActivity(
                NexusUiContract.launchIntent(this@CapabilityOAuthCallbackActivity).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
            finish()
        }
    }
}
