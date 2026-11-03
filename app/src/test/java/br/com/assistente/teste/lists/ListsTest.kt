package br.com.assistente.teste.lists

import android.app.Application
import br.com.assistente.teste.integrations.IntegrationRegistry
import br.com.assistente.teste.llm.AgentPrompts
import br.com.assistente.teste.llm.ChatClient
import br.com.assistente.teste.llm.LlmGateway
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ListsTest {
    private lateinit var app: Application
    private lateinit var database: String
    private lateinit var repository: ListsRepository
    private lateinit var integration: ListsIntegration
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        database = "lists-${UUID.randomUUID()}.db"
        repository = ListsRepository(app, database)
        integration = ListsIntegration(repository)
    }
    @After fun cleanup() { repository.close(); app.deleteDatabase(database) }
    private fun args(vararg fields: Pair<String, Any>) = JSONObject().apply { fields.forEach { put(it.first, it.second) } }
    private fun execute(tool: String, vararg fields: Pair<String, Any>) = JSONObject(integration.execute(tool, args(*fields)))

    @Test fun `data and ordering survive closing and reopening SQLite`() {
        val first = repository.createList("Compras")
        val second = repository.createList("Viagem")
        val rice = repository.addItem(first, "arroz")
        val milk = repository.addItem(first, "leite")
        repository.setCompleted(first, rice, true)
        repository.renameList(first, "Mercado")
        repository.close()
        repository = ListsRepository(app, database)
        val loaded = repository.load()
        assertEquals(listOf(first, second), loaded.map { it.id })
        assertEquals("Mercado", loaded.first().name)
        assertEquals(listOf(rice, milk), loaded.first().items.map { it.id })
        assertTrue(loaded.first().items.first().completed)
        repository.setCompleted(first, rice, false)
        assertFalse(repository.lists.value.first().items.first().completed)
    }
    @Test fun `empty duplicate and case variant list names cannot mutate existing rows`() {
        val id = repository.createList(" Compras ")
        listOf("", " ", "compras", "COMPRAS").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { repository.createList(name) }
        }
        val other = repository.createList("Viagem")
        assertThrows(IllegalArgumentException::class.java) { repository.renameList(other, "compras") }
        assertThrows(IllegalArgumentException::class.java) { repository.addItem(id, "  ") }
        assertEquals(listOf("Compras", "Viagem"), repository.load().map { it.name })
        assertTrue(repository.load().all { it.items.isEmpty() })
    }
    @Test fun `homonyms and cross list IDs only change the requested item`() {
        val list = repository.createList("Compras")
        val other = repository.createList("Viagem")
        val first = repository.addItem(list, "arroz")
        val second = repository.addItem(list, "arroz")
        assertThrows(IllegalArgumentException::class.java) { repository.removeItem(other, first) }
        assertThrows(IllegalArgumentException::class.java) { repository.setCompleted(other, first, true) }
        repository.setCompleted(list, second, true)
        repository.removeItem(list, second)
        assertEquals(listOf(first), repository.load().first().items.map { it.id })
        assertFalse(repository.load().first().items.single().completed)
    }
    @Test fun `tools and direct UI operations publish into the same observable state`() {
        val list = execute("create_list", "name" to "Compras").getString("list_id")
        assertEquals(list, repository.lists.value.single().id)
        val item = execute("add_item", "list_id" to list, "text" to "arroz").getString("item_id")
        assertEquals(item, repository.lists.value.single().items.single().id)
        repository.setCompleted(list, item, true)
        assertEquals(0, execute("list_items", "list_id" to list, "include_completed" to false).getJSONArray("items").length())
        assertTrue(execute("list_items", "list_id" to list, "include_completed" to true).getJSONArray("items").getJSONObject(0).getBoolean("completed"))
        execute("set_item_completed", "list_id" to list, "item_id" to item, "completed" to false)
        execute("rename_list", "list_id" to list, "new_name" to "Mercado")
        assertEquals("Mercado", repository.lists.value.single().name)
        execute("remove_item", "list_id" to list, "item_id" to item)
        assertTrue(repository.lists.value.single().items.isEmpty())
    }
    @Test fun `deletion requires UI confirmation and cascades items after approval only`() {
        val id = repository.createList("Viagem")
        repository.addItem(id, "Passaporte")
        val response = execute("delete_list", "list_id" to id)
        assertFalse(response.getBoolean("deleted"))
        assertTrue(response.getBoolean("requires_confirmation"))
        assertEquals(1, repository.load().single().items.size)
        val request = repository.deletion.value!!
        assertFalse(repository.confirmDeletion("forged-token"))
        assertThrows(IllegalArgumentException::class.java) { execute("delete_list", "list_id" to id, "confirmed" to true) }
        repository.cancelDeletion(request.id)
        assertFalse(repository.confirmDeletion(request.id))
        execute("delete_list", "list_id" to id)
        assertTrue(repository.confirmDeletion(repository.deletion.value!!.id))
        assertTrue(repository.load().isEmpty())
        repository.close()
        repository = ListsRepository(app, database)
        assertTrue(repository.load().isEmpty())
        // Raw query proves the children were actually removed, not just hidden by a join.
        app.openOrCreateDatabase(database, 0, null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM items", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
    @Test fun `changed deletion request is refreshed instead of using an old confirmation`() {
        val id = repository.createList("Viagem")
        val request = repository.requestDeletion(id)
        repository.addItem(id, "Passaporte")
        assertThrows(IllegalStateException::class.java) { repository.confirmDeletion(request.id) }
        assertNotEquals(request.id, repository.deletion.value!!.id)
        assertEquals(1, repository.deletion.value!!.itemCount)
        assertFalse(repository.confirmDeletion(request.id))
        assertEquals(1, repository.load().size)
    }
    @Test fun `strict arguments reject coercion missing fields and invented IDs`() {
        val list = repository.createList("Compras")
        assertThrows(IllegalArgumentException::class.java) { execute("list_items", "list_id" to list) }
        assertThrows(IllegalArgumentException::class.java) { execute("list_items", "list_id" to list, "include_completed" to "false") }
        assertThrows(IllegalArgumentException::class.java) { execute("add_item", "list_id" to "invented", "text" to "arroz") }
        assertTrue(repository.load().single().items.isEmpty())
    }
    @Test fun `network independent tools support empty lists and reads without OAuth`() {
        val registry = IntegrationRegistry(listOf(integration))
        assertEquals(8, registry.tools().length())
        assertEquals(0, JSONObject(registry.execute("list_lists", JSONObject())).getJSONArray("lists").length())
        val list = repository.createList("Compras")
        assertEquals(0, execute("list_items", "list_id" to list, "include_completed" to true).getJSONArray("items").length())
    }
    private fun call(id: String, name: String, arguments: JSONObject) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments.toString()))
    private fun message(vararg calls: JSONObject) = JSONObject().put("role", "assistant").put("tool_calls", JSONArray(calls.toList()))
    @Test fun `real dispatcher returns per item success and partial failure to the LLM`() {
        val list = repository.createList("Compras")
        var step = 0
        val gateway = object : LlmGateway {
            override fun complete(key: String, messages: JSONArray, tools: JSONArray, onProgress: (String) -> Unit): JSONObject {
                return when (step++) {
                    0 -> {
                        assertEquals(8, tools.length())
                        message(call("query", "list_lists", JSONObject()))
                    }
                    1 -> {
                        val actual = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                        assertEquals(list, actual.getJSONArray("lists").getJSONObject(0).getString("id"))
                        message(call("rice", "add_item", args("list_id" to list, "text" to "arroz")),
                            call("invalid", "add_item", args("list_id" to list, "text" to "  ")),
                            call("milk", "add_item", args("list_id" to list, "text" to "leite")))
                    }
                    else -> {
                        val results = (messages.length() - 3 until messages.length()).map { JSONObject(messages.getJSONObject(it).getString("content")) }
                        assertTrue(results[0].getBoolean("ok"))
                        assertTrue(results[1].has("error"))
                        assertTrue(results[2].getBoolean("ok"))
                        JSONObject().put("role", "assistant").put("content", "Adicionei arroz e leite; o item vazio falhou.")
                    }
                }
            }
        }
        val client = ChatClient(gateway, IntegrationRegistry(listOf(integration)), AgentPrompts { File("src/main/assets/agent/prompts/$it").readText() })
        val answer = client.reply("unused-test-key", "Adicione arroz e leite e um item vazio.")
        assertTrue(answer.contains("falhou"))
        assertEquals(listOf("arroz", "leite"), repository.lists.value.single().items.map { it.text })
        assertEquals(3, step)
    }
    @Test fun `concurrent UI and agent writers do not lose items or publish stale snapshots`() {
        val id = repository.createList("Compras")
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (1..4).map { worker -> Thread {
            try { repeat(10) { repository.addItem(id, "$worker-$it") } } catch (t: Throwable) { failures.add(t) }
        }.apply { start() } }
        workers.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(40, repository.lists.value.single().items.size)
        assertEquals(repository.load(), repository.lists.value)
    }
}
