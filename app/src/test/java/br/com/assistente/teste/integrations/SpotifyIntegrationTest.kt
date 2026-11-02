package br.com.assistente.teste.integrations

import br.com.assistente.teste.integrations.spotify.SpotifyIntegration
import br.com.assistente.teste.integrations.spotify.SpotifyTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyIntegrationTest {
    private data class Call(val method: String, val path: String, val query: Map<String, String>, val body: JSONObject?)
    private val calls = mutableListOf<Call>()
    private var connected = true
    private val spotify = SpotifyIntegration({ connected }, SpotifyTransport { method, path, query, body ->
        calls.add(Call(method, path, query.toMap(), body))
        "{\"ok\":true}"
    })
    private val registry = IntegrationRegistry(listOf(spotify))
    private val track = "spotify:track:1234567890123456789012"
    private fun run(json: String) = registry.execute("spotify", JSONObject(json))

    @Test fun `disconnected providers expose no tools and cannot execute`() {
        connected = false
        assertEquals(0, registry.tools().length())
        assertThrows(IllegalStateException::class.java) { run("{\"action\":\"pause\"}") }
        assertTrue(calls.isEmpty())
    }
    @Test fun `registry rejects duplicate providers and unknown tools`() {
        assertThrows(IllegalArgumentException::class.java) { IntegrationRegistry(listOf(spotify, spotify)) }
        assertThrows(IllegalStateException::class.java) { registry.execute("shell", JSONObject()) }
        assertEquals("spotify", registry.tools().getJSONObject(0).getJSONObject("function").getString("name"))
    }
    @Test fun `play forwards only validated parameters`() {
        run("""{"action":"play","uris":["$track"],"device_id":"living-room"}""")
        val call = calls.single()
        assertEquals("PUT", call.method)
        assertEquals("me/player/play", call.path)
        assertEquals("living-room", call.query["device_id"])
        assertEquals(track, call.body!!.getJSONArray("uris").getString(0))
    }
    @Test fun `invalid volume and arbitrary URLs never reach transport`() {
        for (value in listOf("-1", "101", "12.5", "\"50\"")) {
            assertThrows(IllegalArgumentException::class.java) { run("""{"action":"volume","volume_percent":$value}""") }
        }
        assertThrows(IllegalArgumentException::class.java) { run("""{"action":"play","uris":["https://evil.test"]}""") }
        assertThrows(IllegalStateException::class.java) { run("""{"action":"https://evil.test"}""") }
        assertTrue(calls.isEmpty())
    }
    @Test fun `search uses dev mode limit and allows pagination on playlists`() {
        run("""{"action":"search","query":"Bossa nova"}""")
        assertEquals("10", calls.last().query["limit"])
        assertEquals("Bossa nova", calls.last().query["q"])
        run("""{"action":"playlists","offset":20}""")
        assertEquals("20", calls.last().query["offset"])
    }
    @Test fun `library uses generic URI endpoint`() {
        run("""{"action":"save","uris":["$track"]}""")
        assertEquals("PUT", calls.last().method)
        assertEquals("me/library", calls.last().path)
        assertEquals(track, calls.last().query["uris"])
    }
    @Test fun `playlists use current items endpoint and removal payload`() {
        run("""{"action":"remove_playlist_items","playlist_id":"1234567890123456789012","uris":["$track"]}""")
        assertEquals("DELETE", calls.last().method)
        assertEquals("playlists/1234567890123456789012/items", calls.last().path)
        assertEquals(track, calls.last().body!!.getJSONArray("items").getJSONObject(0).getString("uri"))
    }
    @Test fun `new playlists are private and transfer uses device IDs in body`() {
        run("""{"action":"create_playlist","name":"Viagem"}""")
        assertEquals("me/playlists", calls.last().path)
        assertFalse(calls.last().body!!.getBoolean("public"))
        run("""{"action":"transfer","device_id":"speaker"}""")
        assertEquals("me/player", calls.last().path)
        assertTrue(calls.last().query.isEmpty())
        assertEquals("speaker", calls.last().body!!.getJSONArray("device_ids").getString(0))
    }
    @Test fun `library rejects more than forty items before transport`() {
        val arguments = JSONObject().put("action", "save").put("uris", org.json.JSONArray().apply {
            repeat(41) { put(track) }
        })
        assertThrows(IllegalArgumentException::class.java) { registry.execute("spotify", arguments) }
        assertTrue(calls.isEmpty())
    }
    @Test fun `resume pause and next route correctly without a track URI`() {
        run("""{"action":"play"}""")
        assertNull(calls.last().body)
        run("""{"action":"pause"}""")
        assertEquals("PUT", calls.last().method)
        assertEquals("me/player/pause", calls.last().path)
        run("""{"action":"next"}""")
        assertEquals("POST", calls.last().method)
        assertEquals("me/player/next", calls.last().path)
    }
    @Test fun `path traversal and unsupported playable URIs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { run("""{"action":"playlist_items","playlist_id":"../../me"}""") }
        assertThrows(IllegalArgumentException::class.java) { run("""{"action":"play","uris":["spotify:album:1234567890123456789012"]}""") }
        assertTrue(calls.isEmpty())
    }
}
