package io.github.mangi.eta.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Catalog decoding/repair must never run during a speech page's initial composition. */
@Composable
internal fun rememberLocalModelRecords(): State<List<LocalModelRecord>> {
    val context = LocalContext.current.applicationContext
    LocalModelRepository.bind(context)
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { LocalModelRepository.init(context) }
    }
    return LocalModelRepository.models.collectAsState()
}
