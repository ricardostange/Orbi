package br.com.assistente.teste.llm

import android.content.Context
import org.json.JSONObject

class AgentSettings(context: Context) {
    private val assets = context.assets
    private val prefs = context.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)
    fun defaults() = LlmConfig.fromJson(JSONObject(assets.open("agent/provider.json").bufferedReader().use { it.readText() }))
    fun load(): LlmConfig = prefs.getString("config", null)?.let { LlmConfig.fromJson(JSONObject(it)) } ?: defaults()
    fun save(config: LlmConfig) { check(prefs.edit().putString("config", config.toJson().toString()).commit()) }
    fun prompts() = AgentPrompts { name -> assets.open("agent/prompts/$name").bufferedReader().use { it.readText() } }
}
