package br.com.assistente.teste.integrations.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import br.com.assistente.teste.ApiKeyStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** OAuth public client: no client secret belongs in an APK. All persisted auth is encrypted. */
class SpotifyAuth(context: Context, private val client: OkHttpClient) {
    private val store = ApiKeyStore(context, "spotify", "orbi.spotify.oauth.v1")
    private fun record() = store.read()?.let(::JSONObject) ?: JSONObject()
    val connected: Boolean get() = record().optString("refresh_token").isNotEmpty()
    val clientId: String get() = record().optString("client_id").ifBlank { DEFAULT_CLIENT_ID }

    @Synchronized fun authorize(clientId: String): Uri {
        require(clientId.matches(Regex("[a-fA-F0-9]{32}"))) { "Informe um Client ID válido do Spotify." }
        val verifier = random()
        val state = random()
        val data = record()
        if (data.optString("client_id") != clientId) {
            data.remove("access_token"); data.remove("refresh_token"); data.remove("expires_at")
        }
        data.put("client_id", clientId).put("verifier", verifier)
            .put("state", state).put("started_at", System.currentTimeMillis())
        store.save(data.toString())
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI).appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES).appendQueryParameter("show_dialog", "true").build()
    }

    @Synchronized fun complete(uri: Uri) {
        require(isCallback(uri)) { "Retorno do Spotify inválido." }
        val data = record()
        val expected = data.optString("state")
        check(expected.isNotEmpty() && expected == uri.getQueryParameter("state")) { "Autorização inválida. Conecte novamente." }
        check(System.currentTimeMillis() - data.optLong("started_at") in 0L..600_000L) { "Autorização expirada. Conecte novamente." }
        val verifier = data.getString("verifier")
        data.remove("state"); data.remove("verifier"); data.remove("started_at")
        store.save(data.toString()) // One-use state, including on denial/failure.
        check(uri.getQueryParameter("error") == null) { "Autorização do Spotify não concedida." }
        val code = uri.getQueryParameter("code") ?: error("Código do Spotify ausente.")
        exchange(data, FormBody.Builder().add("grant_type", "authorization_code")
            .add("code", code).add("redirect_uri", REDIRECT_URI).add("code_verifier", verifier))
    }

    @Synchronized fun token(forceRefresh: Boolean = false): String {
        val data = record()
        check(data.optString("refresh_token").isNotEmpty()) { "Conecte o Spotify em Integrações." }
        if (!forceRefresh && data.optLong("expires_at") > System.currentTimeMillis() + 60_000)
            return data.getString("access_token")
        return exchange(data, FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", data.getString("refresh_token")), invalidateSessionOnFailure = true)
    }

    private fun exchange(data: JSONObject, form: FormBody.Builder, invalidateSessionOnFailure: Boolean = false): String {
        val request = Request.Builder().url("https://accounts.spotify.com/api/token")
            .post(form.add("client_id", data.getString("client_id")).build()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrDefault("")
                if (reason == "invalid_grant" && invalidateSessionOnFailure) {
                    data.remove("access_token"); data.remove("refresh_token"); store.save(data.toString())
                }
                error(SpotifyErrors.authorization(response.code, reason))
            }
            val result = JSONObject(response.body?.string() ?: error("Resposta vazia do Spotify."))
            data.put("access_token", result.getString("access_token"))
                .put("expires_at", System.currentTimeMillis() + result.getLong("expires_in") * 1000)
            if (result.has("refresh_token")) data.put("refresh_token", result.getString("refresh_token"))
            store.save(data.toString())
            return data.getString("access_token")
        }
    }
    @Synchronized fun disconnect() = store.remove()
    private fun random() = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val DEFAULT_CLIENT_ID = "c69bf2e430f743d6a84b9b3ff6a52b2e"
        const val REDIRECT_URI = "orbi://callback"
        const val SCOPES = "user-read-private user-read-playback-state user-read-currently-playing user-modify-playback-state user-read-recently-played user-library-read user-library-modify playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public"
        fun isCallback(uri: Uri) = uri.scheme == "orbi" && uri.host == "callback" && (uri.path.isNullOrEmpty() || uri.path == "/") && uri.port == -1 && uri.userInfo == null && uri.fragment == null
    }
}
