package aniyomi.csbridge

import android.util.Log

/**
 * Tiny logger that keeps an in-memory ring buffer so the user can read (and
 * copy) what happened inside the bridge straight from the extension settings.
 */
object CsLog {

    private const val TAG = "CSBridge"
    private const val MAX_LINES = 500
    private val buffer = ArrayDeque<String>(MAX_LINES)

    fun d(msg: String, tag: String = TAG) = append("D", tag, msg)
    fun i(msg: String, tag: String = TAG) = append("I", tag, msg)
    fun w(msg: String, tag: String = TAG) = append("W", tag, msg)

    fun e(msg: String, throwable: Throwable? = null, tag: String = TAG) {
        val detail = if (throwable == null) msg else "$msg\n${Log.getStackTraceString(throwable)}"
        append("E", tag, detail)
        Log.e(tag, msg, throwable)
    }

    @Synchronized
    private fun append(level: String, tag: String, msg: String) {
        val line = "${System.currentTimeMillis()} $level/$tag: $msg"
        if (buffer.size >= MAX_LINES) buffer.removeFirst()
        buffer.addLast(line)
        if (level != "E") {
            when (level) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
            }
        }
    }

    @Synchronized
    fun snapshot(): String = if (buffer.isEmpty()) "(no log entry)" else buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()
}
