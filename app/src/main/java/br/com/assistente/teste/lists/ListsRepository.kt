package br.com.assistente.teste.lists

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** All callers run on IO. Transactions and publication share one lock: UI and agent see the same state. */
data class ListItem(val id: String, val listId: String, val text: String, val completed: Boolean)
data class TopicList(val id: String, val name: String, val items: List<ListItem>)
data class DeleteRequest(val id: String, val listId: String, val name: String, val itemCount: Int)

class ListsRepository(context: Context, databaseName: String = "orbi-lists.db") : AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE topics (position INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE items (position INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, list_id TEXT NOT NULL REFERENCES topics(id) ON DELETE CASCADE, text TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)))")
            db.execSQL("CREATE INDEX items_list ON items(list_id, position)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Versão de banco não suportada.") }
    }
    private val lock = Any()
    private val mutableLists = MutableStateFlow<List<TopicList>>(emptyList())
    val lists = mutableLists.asStateFlow()
    private val mutableDeletion = MutableStateFlow<DeleteRequest?>(null)
    val deletion = mutableDeletion.asStateFlow()

    fun load(): List<TopicList> = synchronized(lock) { read(helper.readableDatabase).also { mutableLists.value = it } }
    private fun read(db: SQLiteDatabase): List<TopicList> {
        val items = mutableListOf<ListItem>()
        db.rawQuery("SELECT id,list_id,text,completed FROM items ORDER BY position", null).use { c ->
            while (c.moveToNext()) items += ListItem(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) != 0)
        }
        val grouped = items.groupBy { it.listId }
        return buildList {
            db.rawQuery("SELECT id,name FROM topics ORDER BY position", null).use { c ->
                while (c.moveToNext()) add(TopicList(c.getString(0), c.getString(1), grouped[c.getString(0)].orEmpty()))
            }
        }
    }
    private fun <T> write(action: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        val result = try { action(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
        mutableLists.value = read(db)
        result
    }
    private fun clean(value: String): String = value.trim().also { require(it.isNotEmpty()) { "O nome ou texto não pode ficar vazio." } }
    private fun key(value: String) = value.lowercase(java.util.Locale.ROOT)
    private fun topic(db: SQLiteDatabase, id: String) {
        db.rawQuery("SELECT id FROM topics WHERE id=?", arrayOf(id)).use { require(it.moveToFirst()) { "Lista não encontrada. Consulte list_lists novamente." } }
    }
    private fun availableName(db: SQLiteDatabase, name: String, except: String = "") {
        db.rawQuery("SELECT id FROM topics WHERE name_key=? AND id<>?", arrayOf(key(name), except)).use {
            require(!it.moveToFirst()) { "Já existe uma lista com esse nome. Use a lista existente ou escolha outro nome." }
        }
    }
    fun createList(name: String): String = write { db ->
        val value = clean(name); availableName(db, value)
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("topics", null, ContentValues().apply { put("id", id); put("name", value); put("name_key", key(value)) })
        id
    }
    fun renameList(id: String, name: String) = write { db ->
        topic(db, id); val value = clean(name); availableName(db, value, id)
        db.update("topics", ContentValues().apply { put("name", value); put("name_key", key(value)) }, "id=?", arrayOf(id))
    }
    /** Only the on-device confirmation UI invokes this. Tool arguments cannot approve a deletion. */
    fun deleteList(id: String) = write { db ->
        topic(db, id)
        db.delete("topics", "id=?", arrayOf(id))
    }
    fun requestDeletion(listId: String): DeleteRequest = synchronized(lock) {
        val list = load().firstOrNull { it.id == listId } ?: error("Lista não encontrada.")
        val current = mutableDeletion.value
        check(current == null || current.listId == listId) { "Já há outra exclusão aguardando confirmação na tela." }
        current ?: DeleteRequest(UUID.randomUUID().toString(), list.id, list.name, list.items.size).also { mutableDeletion.value = it }
    }
    fun confirmDeletion(requestId: String): Boolean = synchronized(lock) {
        val request = mutableDeletion.value ?: return@synchronized false
        if (request.id != requestId) return@synchronized false
        // If UI changed the contents after the prompt, require a fresh, accurate confirmation.
        val current = load().firstOrNull { it.id == request.listId }
        if (current == null) { mutableDeletion.value = null; return@synchronized false }
        if (current.name != request.name || current.items.size != request.itemCount) {
            mutableDeletion.value = request.copy(id = UUID.randomUUID().toString(), name = current.name, itemCount = current.items.size)
            error("A lista mudou. Confira a confirmação atualizada.")
        }
        deleteList(request.listId)
        mutableDeletion.value = null
        true
    }
    fun cancelDeletion(requestId: String) = synchronized(lock) {
        if (mutableDeletion.value?.id == requestId) mutableDeletion.value = null
    }
    fun addItem(listId: String, text: String): String = write { db ->
        topic(db, listId); val value = clean(text); val id = UUID.randomUUID().toString()
        db.insertOrThrow("items", null, ContentValues().apply { put("id", id); put("list_id", listId); put("text", value) })
        id
    }
    fun removeItem(listId: String, itemId: String) = write { db ->
        topic(db, listId)
        require(db.delete("items", "id=? AND list_id=?", arrayOf(itemId, listId)) == 1) { "Item não encontrado nessa lista. Consulte list_items novamente." }
    }
    fun setCompleted(listId: String, itemId: String, completed: Boolean) = write { db ->
        topic(db, listId)
        require(db.update("items", ContentValues().apply { put("completed", if (completed) 1 else 0) }, "id=? AND list_id=?", arrayOf(itemId, listId)) == 1) { "Item não encontrado nessa lista. Consulte list_items novamente." }
    }
    override fun close() = synchronized(lock) { helper.close() }
}
