package br.com.assistente.teste

import android.content.Intent
import android.net.Uri
import br.com.assistente.teste.integrations.spotify.SpotifyAuth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpotifyCallbackTest {
    @Test fun `accepts browser callback with or without trailing slash`() {
        assertTrue(SpotifyAuth.isCallback(Uri.parse("orbi://callback?code=test&state=test")))
        assertTrue(SpotifyAuth.isCallback(Uri.parse("orbi://callback/?code=test&state=test")))
        assertTrue(SpotifyAuth.isCallback(Uri.parse("orbi://callback?error=access_denied&state=test")))
    }
    @Test fun `rejects unexpected callback origins paths and fragments`() {
        listOf("https://callback", "orbi://other", "orbi://callback/unexpected", "orbi://callback:80",
            "orbi://user@callback", "orbi://callback#code=test").forEach {
            assertFalse(it, SpotifyAuth.isCallback(Uri.parse(it)))
        }
    }
    @Test fun `manifest routes both callback forms to the dedicated receiver`() {
        val app = RuntimeEnvironment.getApplication()
        listOf("orbi://callback?code=test", "orbi://callback/?code=test").forEach {
            val matches = app.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(it)).addCategory(Intent.CATEGORY_BROWSABLE), 0)
            assertEquals(1, matches.size)
            assertEquals(SpotifyCallbackActivity::class.java.name, matches.single().activityInfo.name)
        }
    }
    @Test fun `receiver forwards original OAuth payload to main screen and finishes`() {
        val uri = Uri.parse("orbi://callback/?code=single-use-code&state=state-value")
        val activity = Robolectric.buildActivity(SpotifyCallbackActivity::class.java,
            Intent(Intent.ACTION_VIEW, uri)).create().get()
        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, forwarded.component!!.className)
        assertEquals(Intent.ACTION_VIEW, forwarded.action)
        assertEquals(uri, forwarded.data)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }
}
