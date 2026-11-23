package com.tappytaps.ttmailer

import android.content.Context

/** Where the SDK keeps its state between launches (SharedPreferences in the app, memory in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

internal class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("com.tappytaps.ttmailer.sdk", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
