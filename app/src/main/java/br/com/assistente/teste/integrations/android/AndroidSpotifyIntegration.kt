package br.com.assistente.teste.integrations.android

import br.com.assistente.teste.integrations.Integration
import org.json.JSONObject

/** Launching an Android app is independent of OAuth and Spotify Connect. */
class AndroidSpotifyIntegration(private val available: () -> Boolean, private val launch: () -> Unit) : Integration {
    override val id = "android"
    override val connected get() = available()
    override val tools get() = listOf(JSONObject("""{
        "type":"function","function":{"name":"android_open_spotify",
        "description":"Abre o aplicativo Spotify instalado no celular. Use para pedidos de abrir Spotify. Não inicia música nem garante dispositivo Connect ativo. Funciona sem OAuth.",
        "parameters":{"type":"object","properties":{},"additionalProperties":false}}
    }"""))
    override fun execute(tool: String, arguments: JSONObject): String {
        require(tool == "android_open_spotify" && arguments.length() == 0) { "Ação Android inválida." }
        check(available()) { "Volte ao Orbi para abrir o Spotify." }
        launch()
        return JSONObject().put("opened", true).put("playback_started", false)
            .put("message", "Abertura solicitada ao Android. Isso não confirma reprodução nem dispositivo Connect ativo.").toString()
    }
}
