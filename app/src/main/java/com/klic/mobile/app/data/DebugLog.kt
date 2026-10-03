package com.klic.mobile.app.data

import android.util.Log
import com.klic.mobile.app.BuildConfig

/**
 * Logcat for debug builds only. E2EE diagnostics mention user/device ids, which must not
 * reach logcat (readable via adb / bug reports) in release builds.
 */
internal object DebugLog {
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, tr)
    }
}
