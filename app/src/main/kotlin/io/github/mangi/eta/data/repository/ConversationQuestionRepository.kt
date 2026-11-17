package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A text-only index; it never loads media or the full historical transcript. */
internal object ConversationQuestionRepository {
    suspend fun index(context: Context, conversationId: String) = withContext(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).conversationDao().questionIndex(conversationId)
    }
}
