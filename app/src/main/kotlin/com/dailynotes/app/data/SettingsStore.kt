package com.dailynotes.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
    fun treeUri(): String = prefs.getString(KEY_TREE_URI, "") ?: ""

    fun save(serverUrl: String, username: String, password: String, rootPath: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .putString(KEY_ROOT_PATH, rootPath.trim().trimEnd('/'))
            .apply()
    }

    fun saveTreeUri(uri: Uri) {
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        // 换树后旧 Uri 全部失效，防止清理页/列表渲染空白（R2-L4）
        AttachmentUriCache.clear()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_ROOT_PATH = "root_path"
        const val KEY_TREE_URI = "tree_uri"
    }
}
