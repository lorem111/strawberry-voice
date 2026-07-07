package com.lorem.strawberry.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer of recent log entries, viewable from the admin-only debug screen.
 */
@Singleton
class DebugLog @Inject constructor() {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timeMillis: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeMillis))
            return "$time ${level.name.first()}/$tag: $message"
        }
    }

    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun add(level: Level, tag: String, message: String) {
        buffer.addLast(Entry(System.currentTimeMillis(), level, tag, message))
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun dump(): String = entries.value.joinToString("\n") { it.format() }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
