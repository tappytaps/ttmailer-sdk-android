package com.tappytaps.ttmailer

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone

/** What the SDK tells TTMailer about the device on every sync. */
data class DeviceInfo(val language: String?, val timeZone: String?, val appVersion: String?, val osVersion: String?) {
    companion object {
        /** The device language, time zone, the app's versionName and the Android version. */
        fun current(context: Context): DeviceInfo {
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { null }
            return DeviceInfo(Locale.getDefault().toLanguageTag(), TimeZone.getDefault().id, version, Build.VERSION.RELEASE)
        }
    }
}
