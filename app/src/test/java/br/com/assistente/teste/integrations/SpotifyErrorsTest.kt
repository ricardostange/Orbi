package br.com.assistente.teste.integrations

import br.com.assistente.teste.integrations.spotify.SpotifyErrors
import org.junit.Assert.*
import org.junit.Test

class SpotifyErrorsTest {
    @Test fun `authorization errors distinguish configuration from expired login`() {
        assertTrue(SpotifyErrors.authorization(400, "invalid_client").contains("Client ID"))
        assertTrue(SpotifyErrors.authorization(400, "invalid_request").contains("orbi://callback"))
        assertTrue(SpotifyErrors.authorization(400, "invalid_grant").contains("Conecte novamente"))
        assertTrue(SpotifyErrors.authorization(400, "invalid_scope").contains("Web API"))
    }
    @Test fun `api errors identify premium device and developer access problems`() {
        assertTrue(SpotifyErrors.api(403, "PREMIUM_REQUIRED", null).contains("Premium"))
        assertTrue(SpotifyErrors.api(403, "RESTRICTION_VIOLATED", null).contains("dispositivo"))
        assertTrue(SpotifyErrors.api(403, "", null).contains("Users and Access"))
        assertTrue(SpotifyErrors.api(404, "", null).contains("inicie uma música"))
        assertTrue(SpotifyErrors.api(401, "", null).contains("Conecte novamente"))
    }
    @Test fun `unknown server content is never displayed and retry interval is validated`() {
        val secret = "access_token=private"
        assertFalse(SpotifyErrors.authorization(500, secret).contains(secret))
        assertFalse(SpotifyErrors.api(500, secret, secret).contains(secret))
        assertTrue(SpotifyErrors.api(429, "", "30").contains("30 segundos"))
        assertTrue(SpotifyErrors.api(429, "", "-1").contains("alguns segundos"))
    }
}
