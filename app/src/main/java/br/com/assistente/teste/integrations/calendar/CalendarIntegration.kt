package br.com.assistente.teste.integrations.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import br.com.assistente.teste.integrations.Integration
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

class CalendarIntegration(private val context: Context) : Integration {
    companion object {
        val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        fun hasAccess(context: Context) = permissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override val id = "calendar"
    override val connected get() = hasAccess(context)
    private val resolver get() = context.contentResolver
    override val tools = listOf(JSONObject("""{
        "type":"function","function":{"name":"calendar",
        "description":"Consulte e gerencie compromissos nas agendas Google sincronizadas. Comece por calendars para obter agendas, data e fuso locais. Use list antes de alterar ou excluir, e peça esclarecimento se houver ambiguidade. Para create escolha a agenda principal se inequívoca. Séries recorrentes são apenas para consulta.",
        "parameters":{"type":"object","properties":{
          "action":{"type":"string","enum":["calendars","list","create","update","delete"]},
          "calendar_id":{"type":"integer","description":"ID retornado por calendars. Opcional em list para consultar todas."},
          "event_id":{"type":"integer","description":"ID retornado por list para update/delete."},
          "expected_start_ms":{"type":"integer","description":"Copie start_ms de list para update/delete."},
          "title":{"type":"string"},
          "start":{"type":"string","description":"list: primeira data inclusiva. Evento: AAAA-MM-DDTHH:mm no fuso do aparelho, ou AAAA-MM-DD para dia inteiro."},
          "end":{"type":"string","description":"Fim exclusivo, no mesmo formato do início. Em update, mover só o início preserva a duração."}
        },"required":["action"],"additionalProperties":false}}
    }"""))

    private data class Agenda(val id: Long, val name: String, val account: String, val writable: Boolean, val primary: Boolean)
    private fun calendars(): List<Agenda> {
        val columns = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY)
        return checkNotNull(resolver.query(Calendars.CONTENT_URI, columns,
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.SYNC_EVENTS} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf("com.google", Calendars.CAL_ACCESS_READ.toString()), null)) { "Falha ao ler agendas." }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Agenda(cursor.getLong(0), cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty(), cursor.getInt(3) >= Calendars.CAL_ACCESS_CONTRIBUTOR, cursor.getInt(4) != 0))
            }
        }
    }

    override fun execute(tool: String, arguments: JSONObject): String {
        require(tool == "calendar" && connected) { "Autorize a agenda nas integrações do Orbi." }
        val zone = ZoneId.systemDefault()
        val agendas = calendars()
        val action = arguments.getString("action")
        if (action == "calendars") return JSONObject().put("now", Instant.now().atZone(zone).toString())
            .put("calendars", JSONArray().apply {
                for (a in agendas) put(JSONObject().put("calendar_id", a.id).put("name", a.name)
                    .put("account", a.account).put("writable", a.writable).put("primary", a.primary))
            }).toString()

        val agenda = if (arguments.has("calendar_id")) requireNotNull(agendas.find { it.id == arguments.getLong("calendar_id") }) {
            "Agenda Google indisponível. Consulte calendars."
        } else null
        if (action == "list") return listEvents(arguments, agenda?.let { listOf(it) } ?: agendas, zone).toString()
        require(agenda != null && agenda.writable) { "Informe o ID de uma agenda Google gravável." }
        return when (action) {
            "create" -> createEvent(arguments, agenda.id, zone)
            "update", "delete" -> changeEvent(action, arguments, agenda.id, zone)
            else -> error("Ação de agenda desconhecida.")
        }.toString()
    }

    private fun listEvents(args: JSONObject, agendas: List<Agenda>, zone: ZoneId): JSONObject {
        val from = LocalDate.parse(args.getString("start"))
        val until = LocalDate.parse(args.getString("end"))
        require(until > from && until <= from.plusDays(93)) { "Consulte um período de 1 a 93 dias." }
        val events = JSONArray()
        val result = JSONObject().put("events", events).put("truncated", false)
        if (agendas.isEmpty()) return result
        val localFrom = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val localEnd = until.atStartOfDay(zone).toInstant().toEpochMilli()
        val utcFrom = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcEnd = until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, minOf(localFrom, utcFrom))
            ContentUris.appendId(this, maxOf(localEnd, utcEnd))
        }.build()
        val columns = arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN,
            Instances.END, Instances.ALL_DAY, Instances.RRULE, Instances.RDATE, Instances.ORIGINAL_ID)
        val placeholders = agendas.joinToString { "?" }
        checkNotNull(resolver.query(uri, columns, "${Instances.CALENDAR_ID} IN ($placeholders)",
            agendas.map { it.id.toString() }.toTypedArray(), "${Instances.BEGIN} ASC")).use { c ->
            while (c.moveToNext()) {
                val start = c.getLong(3)
                val end = c.getLong(4)
                val allDay = c.getInt(5) != 0
                val lower = if (allDay) utcFrom else localFrom
                if (start >= (if (allDay) utcEnd else localEnd) || end < lower || (end == lower && start < lower)) continue
                if (events.length() == 100) { result.put("truncated", true); break }
                fun format(ms: Long): String {
                    val instant = Instant.ofEpochMilli(ms)
                    return if (allDay) instant.atZone(ZoneOffset.UTC).toLocalDate().toString()
                    else instant.atZone(zone).toOffsetDateTime().toString()
                }
                events.put(JSONObject().put("event_id", c.getLong(0)).put("calendar_id", c.getLong(1))
                    .put("title", c.getString(2).orEmpty()).put("start_ms", start)
                    .put("start", format(start)).put("end", format(end)).put("all_day", allDay)
                    .put("recurring", !c.getString(6).isNullOrEmpty() || !c.getString(7).isNullOrEmpty() || !c.isNull(8)))
            }
        }
        return result
    }

    private fun title(args: JSONObject) = args.getString("title").trim().also {
        require(it.isNotEmpty()) { "Informe o título do compromisso." }
    }

    private fun createEvent(args: JSONObject, calendarId: Long, zone: ZoneId): JSONObject {
        val start = args.getString("start")
        val allDay = !start.contains('T')
        val startMs = calendarMillis(start, allDay, zone)
        val endMs = calendarMillis(args.getString("end"), allDay, zone)
        require(endMs > startMs) { "O fim deve ser posterior ao início." }
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title(args))
            put(Events.DTSTART, startMs)
            put(Events.DTEND, endMs)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
        }
        val uri = checkNotNull(resolver.insert(Events.CONTENT_URI, values)) { "Falha ao criar compromisso." }
        return JSONObject().put("created", true).put("event_id", ContentUris.parseId(uri)).put("start_ms", startMs)
    }

    private fun changeEvent(action: String, args: JSONObject, calendarId: Long, zone: ZoneId): JSONObject {
        val eventId = args.getLong("event_id")
        val start = args.getLong("expected_start_ms")
        val where = "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} = ? AND ${Events.DELETED} = 0" +
            " AND COALESCE(${Events.RRULE}, '') = '' AND COALESCE(${Events.RDATE}, '') = '' AND ${Events.ORIGINAL_ID} IS NULL" +
            " AND ${Events.ORIGINAL_SYNC_ID} IS NULL"
        val ids = arrayOf(eventId.toString(), calendarId.toString(), start.toString())
        val stale = "O compromisso mudou, é recorrente ou não está disponível. Consulte a agenda novamente."
        val columns = arrayOf(Events.DTEND, Events.ALL_DAY, Events.TITLE)
        val current = checkNotNull(resolver.query(Events.CONTENT_URI, columns, where, ids, null)).use { c ->
            check(c.moveToFirst()) { stale }
            Triple(c.getLong(0), c.getInt(1) != 0, c.getString(2))
        }
        val (end, allDay, oldTitle) = current
        val selection = "$where AND ${Events.DTEND} = ? AND COALESCE(${Events.TITLE}, '') = ?"
        val selectionArgs = ids + arrayOf(end.toString(), oldTitle.orEmpty())
        val count = if (action == "delete") resolver.delete(Events.CONTENT_URI, selection, selectionArgs) else {
            val values = ContentValues()
            if (args.has("title")) values.put(Events.TITLE, title(args))
            if (args.has("start") || args.has("end")) {
                val newStart = if (args.has("start")) calendarMillis(args.getString("start"), allDay, zone) else start
                val newEnd = if (args.has("end")) calendarMillis(args.getString("end"), allDay, zone) else newStart + (end - start)
                require(newEnd > newStart) { "O fim deve ser posterior ao início." }
                values.put(Events.DTSTART, newStart)
                values.put(Events.DTEND, newEnd)
            }
            require(values.size() > 0) { "Informe o novo título ou horário." }
            resolver.update(Events.CONTENT_URI, values, selection, selectionArgs)
        }
        check(count == 1) { stale }
        return JSONObject().put("event_id", eventId).put(if (action == "delete") "deleted" else "updated", true)
    }
}

internal fun calendarMillis(value: String, allDay: Boolean, zone: ZoneId): Long {
    try {
        if (allDay) return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val local = LocalDateTime.parse(value)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "Horário local ambíguo ou inexistente devido à mudança de fuso." }
        return local.toInstant(offsets.single()).toEpochMilli()
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException(if (allDay) "Use AAAA-MM-DD." else "Use AAAA-MM-DDTHH:mm no fuso do aparelho.")
    }
}
