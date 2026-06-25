package com.triggerbee.sdk

import android.util.Log

/**
 * Pluggable logger so the SDK doesn't take a transitive dependency on Timber or Logback.
 * Most consumers wire in [AndroidLogger] (or leave [NoOpLogger] for release builds).
 */
public interface TriggerbeeLogger {
    public fun debug(message: String, throwable: Throwable? = null)
    public fun info(message: String, throwable: Throwable? = null)
    public fun warn(message: String, throwable: Throwable? = null)
    public fun error(message: String, throwable: Throwable? = null)
}

/** Drops everything. Default when [TriggerbeeConfig.logger] isn't overridden. */
public object NoOpLogger : TriggerbeeLogger {
    override fun debug(message: String, throwable: Throwable?) {}
    override fun info(message: String, throwable: Throwable?) {}
    override fun warn(message: String, throwable: Throwable?) {}
    override fun error(message: String, throwable: Throwable?) {}
}

/** Routes to [Log] under the `Triggerbee` tag. */
public class AndroidLogger : TriggerbeeLogger {
    override fun debug(message: String, throwable: Throwable?) {
        Log.d(TAG, message, throwable)
    }
    override fun info(message: String, throwable: Throwable?) {
        Log.i(TAG, message, throwable)
    }
    override fun warn(message: String, throwable: Throwable?) {
        Log.w(TAG, message, throwable)
    }
    override fun error(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }

    private companion object {
        const val TAG = "Triggerbee"
    }
}
