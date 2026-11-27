package com.headscan

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Records which step a long job is on, so if the app is closed in the middle (a crash inside a native library or
 * the phone running out of memory, which leave no error message) the next start can say where it stopped and why.
 */
object Breadcrumb {
    private fun file(ctx: Context) = File(ctx.filesDir, "step.txt")

    fun set(ctx: Context, step: String) { try { file(ctx).writeText(step) } catch (_: Throwable) {} }
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }

    /** Text describing an unfinished step from last time (and Android's reason the app closed), or null. */
    fun takeReport(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val step = try { f.readText() } catch (_: Throwable) { "" }
        f.delete()
        val sb = StringBuilder("v${BuildConfig.VERSION_NAME}. The app closed while: $step")
        if (Build.VERSION.SDK_INT >= 30) try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
            if (last != null) {
                val why = when (last.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash in native code"
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "phone low on memory"
                    ApplicationExitInfo.REASON_ANR -> "not responding"
                    ApplicationExitInfo.REASON_SIGNALED -> "killed by the system"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
                    else -> "reason ${last.reason}"
                }
                sb.append("\nAndroid says: $why (status ${last.status}, memory ${last.pss / 1024} MB)")
                last.description?.let { sb.append("\n").append(it.take(300)) }
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }
}
