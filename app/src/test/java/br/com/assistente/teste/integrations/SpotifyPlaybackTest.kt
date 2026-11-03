package br.com.assistente.teste.integrations

import br.com.assistente.teste.integrations.spotify.*
import br.com.assistente.teste.integrations.android.AndroidSpotifyIntegration
import br.com.assistente.teste.llm.SafeDiagnostics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaybackTest {
    private val uri = "spotify:track:1234567890123456789012"
    private fun execute(devices: String, state: String = "{}", failPlay: Boolean = false): Pair<JSONObject, List<String>> {
        val calls = mutableListOf<String>()
        val spotify = SpotifyIntegration({ true }, SpotifyTransport { method, path, query, body ->
            calls.add("$method $path")
            when (path) {
                "search" -> """{"tracks":{"items":[{"name":"Clássica","uri":"$uri"}]}}"""
                "me/player/devices" -> devices
                "me/player/play" -> {
                    if (failPlay) error("Este comando exige Spotify Premium na conta conectada.")
                    assertEquals("eligible", query["device_id"])
                    assertEquals(uri, body!!.getJSONArray("uris").getString(0))
                    "{\"ok\":true}"
                }
                "me/player" -> state
                else -> error("Unexpected path")
            }
        })
        return JSONObject(spotify.execute("spotify", JSONObject().put("action", "play_query").put("query", "música clássica"))) to calls
    }

    @Test fun `inactive eligible device receives targeted play and state is verified`() {
        val (result, calls) = execute("""{"devices":[{"id":"eligible","is_active":false,"is_restricted":false}]}""",
            """{"is_playing":true,"item":{"uri":"$uri"}}""")
        assertTrue(result.getBoolean("playback_verified"))
        assertEquals(listOf("GET search", "GET me/player/devices", "PUT me/player/play", "GET me/player"), calls)
    }
    @Test fun `restricted active device is skipped for an eligible inactive device`() {
        val (result, _) = execute("""{"devices":[{"id":"restricted","is_active":true,"is_restricted":true},{"id":"eligible","is_active":false}]}""")
        assertTrue(result.getBoolean("command_accepted"))
        assertFalse(result.getBoolean("playback_verified"))
    }
    @Test fun `no device or only restricted devices never sends play`() {
        listOf("""{"devices":[]}""", """{"devices":[{"id":"restricted","is_restricted":true}]}""").forEach {
            val (result, calls) = execute(it)
            assertFalse(result.getBoolean("play_attempted"))
            assertFalse(calls.contains("PUT me/player/play"))
            assertTrue(result.has("error"))
        }
    }
    @Test fun `play failure is not retried or reported as success`() {
        assertThrows(IllegalStateException::class.java) {
            execute("""{"devices":[{"id":"eligible","is_active":false}]}""", failPlay = true)
        }
    }
    @Test fun `android launch is independent of Spotify auth and never claims playback`() {
        var launched = 0
        val android = AndroidSpotifyIntegration({ true }) { launched++ }
        val result = JSONObject(IntegrationRegistry(listOf(android)).execute("android_open_spotify", JSONObject()))
        assertEquals(1, launched)
        assertTrue(result.getBoolean("opened"))
        assertFalse(result.getBoolean("playback_started"))
        assertThrows(IllegalArgumentException::class.java) { android.execute("shell", JSONObject()) }
        val unavailable = AndroidSpotifyIntegration({ false }) { fail("Must not launch") }
        assertThrows(IllegalStateException::class.java) { unavailable.execute("android_open_spotify", JSONObject()) }
    }
    @Test fun `diagnostic redaction strips token fields and bearer values`() {
        val text = SafeDiagnostics.json(JSONObject("""{"access_token":"secret-a","nested":{"refresh_token":"secret-b","message":"Bearer secret-c"},"devices":[{"id":"eligible","is_active":false}]}"""))
        listOf("secret-a", "secret-b", "secret-c").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("is_active"))
    }
}
