package io.github.mangi.eta.agent.skill

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.fork.learning.LearningDatabase
import org.json.JSONObject
import java.io.File

/** Bridges the installer transaction and Room commit without overwriting a concurrently edited file. */
internal object SkillPublicationJournal {
    private fun prefs(context: Context) = context.getSharedPreferences("skill-publication", 0)

    fun prepare(context: Context, recipe: SkillRecipe, body: String, installedBody: String) {
        check(prefs(context).edit().putString("pending", JSONObject().put("id", recipe.id)
            .put("updatedAt", recipe.updatedAt).put("version", recipe.version + 1).put("body", body)
            .put("installedBody", installedBody.trim()).toString()).commit()) { "无法保存技能提交记录" }
    }

    fun clear(context: Context) { check(prefs(context).edit().remove("pending").commit()) }

    suspend fun recover(context: Context) {
        val raw = prefs(context).getString("pending", null) ?: return
        val pending = JSONObject(raw)
        val db = LearningDatabase.get(context); val dao = db.skillEvolutionDao()
        val recipe = dao.recipe(pending.getString("id")) ?: run { clear(context); return }
        val index = SkillRuntime.createIndexService(context)
        val installed = index.listSkillsForManagement(forceRefresh = true).firstOrNull { it.id == recipe.id && it.installed }
        val file = installed?.let { File(it.rootPath, "SKILL.md") }
        val loaded = installed?.let { SkillRuntime.createLoader(context).load(it, "恢复技能提交") }
        if (file == null || !file.isFile || skillRevision(file.readBytes()) == recipe.revision) { clear(context); return }
        if (recipe.updatedAt == pending.getLong("updatedAt") && loaded?.bodyMarkdown?.trim() == pending.getString("installedBody")) {
            db.withTransaction {
                val next = recipe.copy(body = pending.getString("body"), version = pending.getInt("version"),
                    revision = skillRevision(file.readBytes()), state = "active", updatedAt = System.currentTimeMillis())
                dao.version(SkillRecipeVersion(next.id, next.version, next.title, next.description, next.body, next.tools, next.updatedAt))
                dao.save(next)
            }
            index.setSkillEnabled(recipe.id, true)
        } else {
            index.setSkillEnabled(recipe.id, false)
            dao.save(recipe.copy(state = "conflict", updatedAt = System.currentTimeMillis()))
        }
        clear(context)
    }
}
