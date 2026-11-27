package com.example.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Lazy bridge from Kotlin to the embedded on-device CPython runtime. */
class PythonEngineBridge(context: Context) {
    private val appContext = context.applicationContext

    @Synchronized
    private fun python(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        return Python.getInstance()
    }

    fun health(): PythonEngineHealth {
        val result: PyObject = python().getModule("iq_engine.engine").callAttr("engine_status")
        return PythonEngineHealth(
            runtime = result.get("runtime")?.toString().orEmpty(),
            engineVersion = result.get("engine_version")?.toString().orEmpty(),
            ready = result.get("ready")?.toString()?.toBoolean() ?: false
        )
    }

    fun analyzeCandles(candlesJson: String): String {
        require(candlesJson.isNotBlank()) { "Candles JSON must not be blank." }
        return python().getModule("iq_engine.engine").callAttr("analyze_candles_json", candlesJson).toString()
    }

    /** Practice-only, read-only handshake. Call from Dispatchers.IO, never the UI thread. */
    fun connectPracticeReadOnly(email: String, password: String): String {
        require(email.isNotBlank()) { "Email is required." }
        require(password.isNotEmpty()) { "Password is required." }
        return python().getModule("iq_engine.iqair_adapter")
            .callAttr("connect_practice_readonly_json", email, password).toString()
    }

    fun disconnectIqOption(): String =
        python().getModule("iq_engine.iqair_adapter").callAttr("disconnect_json").toString()

    fun iqOptionAdapterStatus(): String =
        python().getModule("iq_engine.iqair_adapter").callAttr("adapter_status_json").toString()
}

data class PythonEngineHealth(val runtime: String, val engineVersion: String, val ready: Boolean)
