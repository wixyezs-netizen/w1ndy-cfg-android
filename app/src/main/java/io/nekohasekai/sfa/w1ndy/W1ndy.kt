package io.nekohasekai.sfa.w1ndy

import android.content.Context
import android.content.SharedPreferences
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date

/**
 * Аккаунт W1NDY CFG: вход через Telegram-бота, данные подписки и автоматический профиль.
 */
object W1ndy {
    const val BASE_URL = "https://site.litenergyapp.store"
    const val BOT_URL = "https://t.me/VPN_LitEnergy_bot"
    const val PLANS_URL = "https://site.litenergyapp.store/plans/"
    const val PROFILE_NAME = "W1NDY"

    private const val PREFS = "w1ndy_account"
    private const val K_TOKEN = "token"
    private const val K_TOKEN_AT = "token_at"
    private const val K_NAME = "name"
    private const val K_SUB_URL = "sub_url"
    private const val K_STATUS = "status"
    private const val K_EXPIRE = "expire_at"
    private const val K_DAYS = "days_left"
    private const val K_PROFILE_ID = "profile_id"
    private const val TOKEN_REFRESH_MS = 7L * 24 * 3600 * 1000

    data class Account(
        val loggedIn: Boolean,
        val name: String,
        val subUrl: String,
        val status: String,
        val expireAt: String,
        val daysLeft: Int,
    ) {
        val hasLink: Boolean get() = subUrl.isNotEmpty()
        val active: Boolean get() = status == "ACTIVE"

        /** "2026-11-21T10:00:00Z" -> "21.11.2026" */
        val expireDate: String
            get() {
                val d = expireAt.take(10).split("-")
                return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else ""
            }
    }

    class ApiException(val code: Int, message: String) : Exception(message)

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Account {
        val p = prefs(context)
        return Account(
            loggedIn = !p.getString(K_TOKEN, null).isNullOrEmpty(),
            name = p.getString(K_NAME, "") ?: "",
            subUrl = p.getString(K_SUB_URL, "") ?: "",
            status = p.getString(K_STATUS, "") ?: "",
            expireAt = p.getString(K_EXPIRE, "") ?: "",
            daysLeft = p.getInt(K_DAYS, 0),
        )
    }

    fun logout(context: Context) {
        prefs(context).edit()
            .remove(K_TOKEN).remove(K_TOKEN_AT).remove(K_NAME)
            .remove(K_STATUS).remove(K_EXPIRE).remove(K_DAYS)
            .apply()
    }

    private fun get(path: String, token: String? = null): JSONObject {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "W1NDY-CFG/${BuildConfig.VERSION_NAME} (Android)")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("X-LitEnergy-Session", token)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                JSONObject()
            }
            if (code !in 200..299) {
                throw ApiException(code, json.optString("message").ifEmpty { "Ошибка сервера ($code)" })
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /** Шаг 1: код входа и ссылка на бота. */
    suspend fun requestLogin(): Pair<String, String> = withContext(Dispatchers.IO) {
        val j = get("/api/web/auth/request-bot?client=app")
        val code = j.optString("code")
        val url = j.optString("url")
        if (code.isEmpty() || url.isEmpty()) throw ApiException(500, "Не удалось начать вход")
        code to url
    }

    /** Шаг 2: опрос подтверждения. true — вход выполнен, токен сохранён. */
    suspend fun pollLogin(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val j = get("/api/web/auth/poll-bot?code=" + URLEncoder.encode(code, "UTF-8"))
        if (j.optString("status") != "authenticated") return@withContext false
        val token = j.optString("token")
        if (token.isEmpty()) return@withContext false
        val user = j.optJSONObject("user")
        prefs(context).edit()
            .putString(K_TOKEN, token)
            .putLong(K_TOKEN_AT, System.currentTimeMillis())
            .putString(K_NAME, user?.optString("first_name").orEmpty())
            .apply()
        true
    }

    /**
     * Обновляет данные аккаунта с сервера. При [sync] (или если ссылка изменилась) —
     * скачивает конфигурацию и выбирает профиль W1NDY.
     */
    suspend fun refresh(context: Context, sync: Boolean = false): Account = withContext(Dispatchers.IO) {
        val p = prefs(context)
        var token = p.getString(K_TOKEN, null)
        if (token.isNullOrEmpty()) return@withContext load(context)
        if (System.currentTimeMillis() - p.getLong(K_TOKEN_AT, 0L) > TOKEN_REFRESH_MS) {
            runCatching { get("/api/web/auth/refresh", token) }.onSuccess { j ->
                val t = j.optString("token")
                if (t.isNotEmpty()) {
                    token = t
                    p.edit().putString(K_TOKEN, t).putLong(K_TOKEN_AT, System.currentTimeMillis()).apply()
                }
            }
        }
        val j = try {
            get("/api/miniapp/bootstrap", token)
        } catch (e: ApiException) {
            if (e.code == 401) {
                logout(context)
                throw ApiException(401, "Сессия истекла — войдите ещё раз")
            }
            throw e
        }
        val sub = j.optJSONObject("subscription")
        val user = j.optJSONObject("user")
        val url = sub?.optString("url").orEmpty().let { if (it == "null") "" else it }
        val oldUrl = p.getString(K_SUB_URL, "") ?: ""
        p.edit()
            .putString(K_NAME, user?.optString("first_name").orEmpty().ifEmpty { p.getString(K_NAME, "") ?: "" })
            .putString(K_STATUS, sub?.optString("status").orEmpty())
            .putString(K_EXPIRE, sub?.optString("expire_at").orEmpty().let { if (it == "null") "" else it })
            .putInt(K_DAYS, sub?.optInt("days_left", 0) ?: 0)
            .apply()
        if (url.isNotEmpty() && (sync || url != oldUrl || findProfile(context) == null)) {
            syncProfile(context, url)
        }
        load(context)
    }

    /** Ссылка из кабинета: https://… или sing-box://import-remote-profile?url=… */
    fun normalizeLink(raw: String): String? {
        val s = raw.trim()
        if (s.startsWith("sing-box://")) {
            return runCatching { Libbox.parseRemoteProfileImportLink(s).url }.getOrNull()
        }
        if (s.startsWith("https://") || s.startsWith("http://")) return s
        return null
    }

    private suspend fun findProfile(context: Context): Profile? {
        val id = prefs(context).getLong(K_PROFILE_ID, -1L)
        if (id > 0) ProfileManager.get(id)?.let { return it }
        return null
    }

    /** Создаёт/обновляет профиль W1NDY с этой ссылкой, скачивает конфигурацию и делает его активным. */
    suspend fun syncProfile(context: Context, url: String): Profile = withContext(Dispatchers.IO) {
        val content = HTTPClient().use { it.getString(url) }
        Libbox.checkConfig(content)

        var profile = findProfile(context)
        if (profile == null) {
            val typed = TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = url
                autoUpdate = true
                autoUpdateInterval = 60
                lastUpdated = Date()
            }
            val fileID = ProfileManager.nextFileID()
            val dir = File(context.filesDir, "configs").also { it.mkdirs() }
            val file = File(dir, "$fileID.json")
            file.writeText(content)
            typed.path = file.path
            profile = Profile(name = PROFILE_NAME, typed = typed).apply {
                userOrder = ProfileManager.nextOrder()
            }
            profile = ProfileManager.create(profile, andSelect = true)
        } else {
            val file = File(profile.typed.path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            profile.typed.type = TypedProfile.Type.Remote
            profile.typed.remoteURL = url
            profile.typed.autoUpdate = true
            profile.typed.lastUpdated = Date()
            ProfileManager.update(profile)
            if (Settings.selectedProfile != profile.id) {
                Settings.selectedProfile = profile.id
            } else {
                runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
            }
        }
        prefs(context).edit()
            .putLong(K_PROFILE_ID, profile.id)
            .putString(K_SUB_URL, url)
            .apply()
        profile
    }
}
