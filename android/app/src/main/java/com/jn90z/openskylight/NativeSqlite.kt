package com.jn90z.openskylight

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** All methods are called on the backend's serial thread. */
internal class NativeSqlite(context: Context) {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(
        context.getDatabasePath("openskylight.db").also { it.parentFile?.mkdirs() }, null
    ).apply {
        enableWriteAheadLogging()
        setForeignKeyConstraintsEnabled(true)
    }
    val version: Int get() = db.version

    fun begin() = db.beginTransactionNonExclusive()
    fun commit() = db.setTransactionSuccessful()
    fun end() = db.endTransaction()

    fun query(call: JSONArray): JSONObject {
        val sql = call.getString(0)
        val args = call.getJSONArray(1)
        val rows = JSONArray()
        return db.rawQueryWithFactory({ _, driver, table, query ->
            query.bind(args)
            android.database.sqlite.SQLiteCursor(driver, table, query)
        }, sql, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONArray()
                for (column in 0 until cursor.columnCount) row.put(
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP))
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        else -> JSONObject.NULL
                    }
                )
                rows.put(row)
            }
            JSONObject().put("columns", JSONArray(cursor.columnNames.toList())).put("rows", rows)
        }
    }

    fun run(call: JSONArray): Int {
        val sql = call.getString(0)
        val args = call.getJSONArray(1)
        return db.compileStatement(sql).use { statement ->
            statement.bind(args)
            when (sql.trimStart().substringBefore(' ').lowercase()) {
                "update", "delete", "insert", "replace" -> statement.executeUpdateDelete()
                else -> { statement.execute(); 0 }
            }
        }
    }

    private fun SQLiteProgram.bind(args: JSONArray) {
        for (i in 0 until args.length()) {
            val index = i + 1
            when (val value = args.get(i)) {
                JSONObject.NULL -> bindNull(index)
                is JSONObject -> bindBlob(index, Base64.decode(value.getString("blob"), Base64.DEFAULT))
                is Int -> bindLong(index, value.toLong())
                is Long -> bindLong(index, value)
                is Number -> bindDouble(index, value.toDouble())
                is Boolean -> bindLong(index, if (value) 1 else 0)
                is String -> bindString(index, value)
                else -> error("Unsupported SQLite value")
            }
        }
    }
}
