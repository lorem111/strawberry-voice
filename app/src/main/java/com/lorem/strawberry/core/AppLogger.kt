package com.lorem.strawberry.core

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide logger. Writes to Logcat and mirrors everything into [DebugLog] so admins can
 * inspect logs in-app. Inject this instead of calling android.util.Log directly.
 */
interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

@Singleton
class AndroidAppLogger @Inject constructor(
    private val debugLog: DebugLog
) : AppLogger {

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
        debugLog.add(DebugLog.Level.DEBUG, tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
        debugLog.add(DebugLog.Level.INFO, tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
        debugLog.add(DebugLog.Level.WARN, tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        val detail = throwable?.let { "$message — ${it.javaClass.simpleName}: ${it.message}" } ?: message
        debugLog.add(DebugLog.Level.ERROR, tag, detail)
    }
}
