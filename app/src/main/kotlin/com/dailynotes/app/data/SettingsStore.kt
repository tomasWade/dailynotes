package com.dailynotes.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "dailynotes_settings",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun serverUrl(): String = prefs.getString(KEY_SERVER_URL, "") ?: ""
    fun username(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    fun password(): String = prefs.getString(KEY_PASSWORD, "") ?: ""
    fun rootPath(): String = prefs.getString(KEY_ROOT_PATH, "") ?: ""

    /** 是否成功完成过至少一次同步（pull 成功即置位，持久）。
     *  用途（R5-Issue1 收紧）：升级换缓存目录/新装机后 cache-state 为空，hasRemoteRecord 恒 false——
     *  离线首会话打开远端已有文件会拿到「新文件语义+可保存」，LWW 无锁覆盖远端。
     *  凡「服务器已配置但从未同步成功」+缓存缺失 → 按 loadFailed 处理禁保存，直到完成首次同步。 */
    fun everSynced(): Boolean = prefs.getBoolean(KEY_EVER_SYNCED, false)
    fun markEverSynced() = prefs.edit().putBoolean(KEY_EVER_SYNCED, true).apply()
    fun save(serverUrl: String, username: String, password: String, rootPath: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .putString(KEY_ROOT_PATH, rootPath.trim().trimEnd('/'))
            .apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_ROOT_PATH = "root_path"
        const val KEY_EVER_SYNCED = "ever_synced"
    }
}
