package br.com.assistente.teste.integrations

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import br.com.assistente.teste.integrations.calendar.CalendarIntegration
import br.com.assistente.teste.integrations.calendar.calendarMillis
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CalendarIntegrationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val integration = CalendarIntegration(app)
    private val provider = TestCalendarProvider()

    private fun setup() {
        shadowOf(app).grantPermissions(*CalendarIntegration.permissions)
        provider.attachInfo(app, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }

    private fun call(action: String, vararg fields: Pair<String, Any>) = JSONObject(integration.execute(
        "calendar", JSONObject().put("action", action).apply { fields.forEach { (k, v) -> put(k, v) } }))

    @Test fun manageAllDayEvent() {
        setup()
        val start = Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()
        assertEquals(1, call("calendars").getJSONArray("calendars").length())
        val common = arrayOf("calendar_id" to 1, "event_id" to 42)
        assertEquals(42, call("create", "calendar_id" to 1, "title" to "Folga",
            "start" to "2026-11-27", "end" to "2026-11-28").getInt("event_id"))
        val listed = call("list", "start" to "2026-11-27", "end" to "2026-11-28").getJSONArray("events").getJSONObject(0)
        assertEquals(start, listed.getLong("start_ms"))
        assertTrue(listed.getBoolean("all_day"))
        call("update", *common, "expected_start_ms" to start, "start" to "2026-11-30")
        val moved = start + 3 * 86_400_000L
        assertEquals(moved + 86_400_000L, provider.event!!.getAsLong(Events.DTEND).toLong())
        assertThrows(IllegalStateException::class.java) { call("delete", *common, "expected_start_ms" to start) }
        call("delete", *common, "expected_start_ms" to moved)
        assertNull(provider.event)
    }

    @Test fun refusesReadOnlyCalendarAndInvalidRange() {
        setup()
        provider.readOnly = true
        assertThrows(IllegalArgumentException::class.java) { call("create", "calendar_id" to 1) }
        provider.readOnly = false
        assertThrows(IllegalArgumentException::class.java) {
            call("create", "calendar_id" to 1, "title" to "Teste", "start" to "2026-11-28", "end" to "2026-11-27")
        }
        assertNull(provider.event)
    }

    @Test fun timedEventsUseDeviceZoneAndAllDayUsesUtc() {
        val zone = ZoneId.of("America/Sao_Paulo")
        assertEquals(Instant.parse("2026-11-27T17:00:00Z").toEpochMilli(), calendarMillis("2026-11-27T14:00", false, zone))
        assertEquals(Instant.parse("2026-11-27T00:00:00Z").toEpochMilli(), calendarMillis("2026-11-27", true, zone))
        assertThrows(IllegalArgumentException::class.java) {
            calendarMillis("2026-11-01T01:30", false, ZoneId.of("America/New_York"))
        }
    }

    private class TestCalendarProvider : ContentProvider() {
        var event: ContentValues? = null
        var readOnly = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?) = MatrixCursor(projection!!).apply {
            when (uri.pathSegments.first()) {
                "calendars" -> addRow(arrayOf<Any>(1, "Agenda", "conta", if (readOnly) 200 else 700, 1))
                "instances" -> event?.let { e -> addRow(arrayOf<Any?>(42, 1, e[Events.TITLE], e[Events.DTSTART],
                    e[Events.DTEND], e[Events.ALL_DAY], null, null, null)) }
                "events" -> event?.takeIf { it.getAsString(Events.DTSTART) == selectionArgs?.get(2) }?.let { e ->
                    addRow(arrayOf(e[Events.DTEND], e[Events.ALL_DAY], e[Events.TITLE]))
                }
                else -> error("URI inesperada: $uri")
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            event = ContentValues(values!!)
            return ContentUris.withAppendedId(uri, 42)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            event!!.putAll(values!!)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            event = null
            return 1
        }
    }
}
