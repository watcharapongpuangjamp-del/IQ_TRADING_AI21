package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

object FirebaseBootstrap {
    private const val TAG = "FirebaseBootstrap"

    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }
        return try {
            val initialized = FirebaseApp.initializeApp(appContext)
            if (initialized != null || FirebaseApp.getApps(appContext).isNotEmpty()) {
                true
            } else {
                val options = FirebaseOptions.fromResource(appContext)
                if (options != null) {
                    FirebaseApp.initializeApp(appContext, options)
                    FirebaseApp.getApps(appContext).isNotEmpty()
                } else {
                    Log.w(
                        TAG,
                        "FirebaseOptions.fromResource returned null; google-services.json resources are missing from this build."
                    )
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseApp", e)
            false
        }
    }

    fun getAuthOrNull(context: Context): FirebaseAuth? {
        if (!ensureInitialized(context)) return null
        return try {
            Firebase.auth
        } catch (e: IllegalStateException) {
            Log.e(TAG, "FirebaseAuth unavailable despite initialization check", e)
            null
        }
    }
}

class IQTradingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseBootstrap.ensureInitialized(this)
    }
}
