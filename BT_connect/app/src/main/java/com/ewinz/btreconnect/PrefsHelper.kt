package com.ewinz.btreconnect

import android.content.Context

object PrefsHelper {
    private const val PREF = "bt_reconnect_prefs"
    private const val KEY_NAME = "target_name"
    private const val KEY_LAST_MAC = "last_mac"

    /** Simpan nama target (misal "bloutooth") */
    fun saveName(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, name.trim()).apply()
    }

    fun getName(ctx: Context): String? {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
    }

    /** MAC terakhir yang ditemukan — hanya untuk referensi/log, bukan untuk connect */
    fun saveLastMac(ctx: Context, mac: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_MAC, mac).apply()
    }

    fun getLastMac(ctx: Context): String? {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MAC, null)
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}