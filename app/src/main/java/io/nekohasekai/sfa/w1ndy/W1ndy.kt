package io.nekohasekai.sfa.w1ndy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import java.util.UUID

/**
 * W1NDY CFG: доступ по коду (WireGuard-конфиг из пула администратора)
 * и отдельный режим «только DNS».
 */
object W1ndy {
    const val BASE_URL = "https://site.litenergyapp.store"
    const val BOT_URL = "https://t.me/VPN_LitEnergy_bot"
    const val CHANNEL_URL = "https://t.me/+65OxDmkfjv04ZDhh"
    const val APK_URL = "https://site.litenergyapp.store/download/W1NDY-CFG.apk"
    private const val PROFILE_VPN = "W1NDY VPN"
    private const val PROFILE_DNS = "W1NDY DNS"

    const val MODE_VPN = "vpn"
    const val MODE_DNS = "dns"

    data class DnsPreset(val title: String, val address: String)

    val DNS_PRESETS = listOf(
        DnsPreset("Google", "8.8.8.8"),
        DnsPreset("Cloudflare", "1.1.1.1"),
        DnsPreset("Яндекс", "77.88.8.8"),
        DnsPreset("AdGuard", "94.140.14.14"),
    )

    private const val PREFS = "w1ndy_app"
    private const val K_INSTALL = "install_id"
    private const val K_TOKEN = "token"
    private const val K_NAME = "conf_name"
    private const val K_MODE = "mode"
    private const val K_DNS = "dns_server"
    private const val K_VPN_ID = "vpn_profile_id"
    private const val K_DNS_ID = "dns_profile_id"
    private const val K_TG = "tg_linked"
    private const val K_TG_NAME = "tg_name"
    private const val K_ONBOARDED = "onboarded"
    private const val K_CONNECTED_AT = "connected_at"

    data class State(
        val activated: Boolean,
        val confName: String,
        val mode: String,
        val dnsServer: String,
        val tgLinked: Boolean = false,
        val tgName: String = "",
        val onboarded: Boolean = false,
    ) {
        /** Название профиля на главном экране. */
        val title: String get() = if (mode == MODE_DNS) "DNS · $dnsServer" else confName.ifEmpty { "W1NDY VPN" }
    }

    class ApiException(val code: Int, message: String) : Exception(message)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): State {
        val p = prefs(context)
        return State(
            activated = !p.getString(K_TOKEN, null).isNullOrEmpty(),
            confName = p.getString(K_NAME, "") ?: "",
            mode = p.getString(K_MODE, MODE_VPN) ?: MODE_VPN,
            dnsServer = p.getString(K_DNS, DNS_PRESETS[0].address) ?: DNS_PRESETS[0].address,
            tgLinked = p.getBoolean(K_TG, false),
            tgName = p.getString(K_TG_NAME, "") ?: "",
            onboarded = p.getBoolean(K_ONBOARDED, false),
        )
    }

    fun setOnboarded(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_ONBOARDED, value).apply()
    }

    /** Время подключения для таймера на главном экране. */
    fun connectedAt(context: Context, connected: Boolean): Long {
        val p = prefs(context)
        if (!connected) {
            p.edit().remove(K_CONNECTED_AT).apply()
            return 0L
        }
        val t = p.getLong(K_CONNECTED_AT, 0L)
        if (t > 0) return t
        val now = System.currentTimeMillis()
        p.edit().putLong(K_CONNECTED_AT, now).apply()
        return now
    }

    /** Ссылка на «Старт» в @VPN_LitEnergy_bot для этой установки. */
    suspend fun telegramLink(context: Context): String = withContext(Dispatchers.IO) {
        val j = request("POST", "/api/w1ndy/tg/start", JSONObject().put("install_id", installId(context)))
        val url = j.optString("url").ifEmpty { throw ApiException(500, "Не удалось получить ссылку") }
        // одноразовый код привязки; нужен автотесту сборки (логи доступны только через adb)
        android.util.Log.i("W1NDY", "tg-link " + url.substringAfter("w1_", ""))
        url
    }

    /** true — пользователь нажал «Старт» в боте. */
    suspend fun telegramLinked(context: Context): Boolean = withContext(Dispatchers.IO) {
        val j = request("GET", "/api/w1ndy/tg/poll?install_id=" + URLEncoder.encode(installId(context), "UTF-8"))
        val linked = j.optBoolean("linked", false)
        if (linked) {
            prefs(context).edit().putBoolean(K_TG, true).putString(K_TG_NAME, j.optString("name")).apply()
        }
        linked
    }

    private fun installId(context: Context): String {
        val p = prefs(context)
        p.getString(K_INSTALL, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        p.edit().putString(K_INSTALL, id).apply()
        return id
    }

    private const val NET_ERROR = "Сервер не отвечает. Проверьте интернет и попробуйте ещё раз."

    /**
     * Запрос к API. Каждый раз новое соединение (без keep-alive): на части мобильных сетей
     * долгоживущие соединения через CDN «замирают» после нескольких килобайт — короткие проходят.
     * Сетевые сбои повторяются до 3 раз; активация повторяется, только если запрос не ушёл на сервер.
     */
    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val idempotent = !path.startsWith("/api/w1ndy/activate")
        var last: java.io.IOException? = null
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(800L * attempt)
            try {
                return requestOnce(method, path, body)
            } catch (e: java.io.IOException) {
                last = e
                val notSent = e is java.net.ConnectException || e is java.net.UnknownHostException ||
                    e is java.net.NoRouteToHostException ||
                    (e is java.net.SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
                if (!idempotent && !notSent) throw ApiException(0, NET_ERROR)
            }
        }
        android.util.Log.w("W1NDY", "request failed: $path", last)
        throw ApiException(0, NET_ERROR)
    }

    private fun requestOnce(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "W1NDY-CFG/${BuildConfig.VERSION_NAME} (Android)")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                if (code in 520..530 || code == 502 || code == 504) throw java.io.IOException("gateway $code")
                throw ApiException(code, json.optString("message").ifEmpty { "Ошибка сервера ($code)" })
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun profileUrl(token: String) = "$BASE_URL/api/w1ndy/profile?t=" + URLEncoder.encode(token, "UTF-8")

    private fun deviceModel(): String {
        val m = Build.MODEL ?: ""
        val b = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }
        return (if (m.startsWith(b, ignoreCase = true)) m else "$b $m").trim().take(64)
    }

    /** Активация по коду: сервер выдаёт свободный конфиг, приложение создаёт профиль и выбирает его. */
    suspend fun activate(context: Context, code: String): State = withContext(Dispatchers.IO) {
        val j = request(
            "POST",
            "/api/w1ndy/activate",
            JSONObject()
                .put("code", code.trim())
                .put("install_id", installId(context))
                .put("model", deviceModel()),
        )
        val token = j.optString("token")
        if (token.isEmpty()) throw ApiException(500, "Сервер не выдал доступ")
        prefs(context).edit()
            .putString(K_TOKEN, token)
            .putString(K_NAME, j.optString("name"))
            .putString(K_MODE, MODE_VPN)
            .apply()
        syncVpnProfile(context, profileUrl(token))
        load(context)
    }

    /** Проверка доступа (403 — доступ отключён администратором: забываем код и профиль). */
    private fun checkAccess(context: Context, token: String) {
        try {
            val j = request("GET", "/api/w1ndy/status?t=" + URLEncoder.encode(token, "UTF-8"))
            prefs(context).edit().putString(K_NAME, j.optString("name")).apply()
        } catch (e: ApiException) {
            if (e.code == 403) throw ApiException(403, "Доступ отключён администратором. Введите новый код.")
            throw e
        }
    }

    /** При открытии экрана. */
    suspend fun refresh(context: Context): State = withContext(Dispatchers.IO) {
        val token = prefs(context).getString(K_TOKEN, null)
        if (token.isNullOrEmpty()) return@withContext load(context)
        try {
            checkAccess(context, token)
        } catch (e: ApiException) {
            if (e.code == 403) reset(context)
            throw e
        }
        if (findProfile(K_VPN_ID, context) == null) syncVpnProfile(context, profileUrl(token))
        load(context)
    }

    /** Повторно скачать конфиг (если администратор что-то поменял). */
    suspend fun updateVpn(context: Context): State = withContext(Dispatchers.IO) {
        val token = prefs(context).getString(K_TOKEN, null) ?: throw ApiException(401, "Сначала введите код")
        try {
            checkAccess(context, token)
        } catch (e: ApiException) {
            if (e.code == 403) reset(context)
            throw e
        }
        syncVpnProfile(context, profileUrl(token))
        load(context)
    }

    /** Забыть код и удалить профиль VPN. */
    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        findProfile(K_VPN_ID, context)?.let { ProfileManager.delete(it) }
        prefs(context).edit().remove(K_TOKEN).remove(K_NAME).remove(K_VPN_ID).apply()
    }

    /** «Выйти»: удалить ключ и вернуться к экранам входа. */
    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        reset(context)
        prefs(context).edit().putBoolean(K_ONBOARDED, false).apply()
    }

    /** Переключение режима VPN / только DNS. */
    suspend fun setMode(context: Context, mode: String): State = withContext(Dispatchers.IO) {
        prefs(context).edit().putString(K_MODE, mode).apply()
        if (mode == MODE_DNS) {
            applyDns(context, load(context).dnsServer)
        } else {
            findProfile(K_VPN_ID, context)?.let { select(it) }
        }
        load(context)
    }

    // ---------- DNS ----------

    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    /** Только IP-адрес (IPv4 или IPv6), без доменов. null — неверный. */
    fun normalizeDns(raw: String): String? {
        val s = raw.trim()
        if (IPV4.matches(s)) return s
        if (s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return runCatching { InetAddress.getByName(s).hostAddress }.getOrNull()
        }
        return null
    }

    fun dnsConfig(server: String): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("level", "warn"))
        root.put(
            "dns",
            JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("type", "udp").put("tag", "dns-custom").put("server", server)))
                .put("final", "dns-custom"),
        )
        // В туннель попадает только адрес DNS-сервера VPN (172.19.0.2), остальной трафик идёт напрямую.
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("address", JSONArray().put("172.19.0.1/30"))
                    .put("route_address", JSONArray().put("172.19.0.0/30"))
                    .put("auto_route", true)
                    .put("stack", "mixed"),
            ),
        )
        root.put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
        root.put(
            "route",
            JSONObject()
                .put(
                    "rules",
                    JSONArray()
                        .put(JSONObject().put("action", "sniff"))
                        .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                        .put(JSONObject().put("port", 53).put("action", "hijack-dns")),
                )
                .put("final", "direct")
                .put("auto_detect_interface", true),
        )
        return root.toString(2)
    }

    /** Создаёт/обновляет профиль «только DNS» с этим сервером и выбирает его. */
    suspend fun applyDns(context: Context, raw: String): State = withContext(Dispatchers.IO) {
        val server = normalizeDns(raw) ?: throw ApiException(400, "Введите IP-адрес DNS, например 8.8.8.8")
        val content = dnsConfig(server)
        Libbox.checkConfig(content)
        val profile = writeProfile(context, K_DNS_ID, "$PROFILE_DNS · $server", TypedProfile.Type.Local, null, content)
        prefs(context).edit().putString(K_DNS, server).putString(K_MODE, MODE_DNS).apply()
        select(profile, forceReload = true)
        load(context)
    }

    // ---------- профили ----------

    private suspend fun findProfile(key: String, context: Context): Profile? {
        val id = prefs(context).getLong(key, -1L)
        return if (id > 0) ProfileManager.get(id) else null
    }

    private suspend fun syncVpnProfile(context: Context, url: String): Profile {
        val content = HTTPClient().use { it.getString(url) }
        Libbox.checkConfig(content)
        val profile = writeProfile(context, K_VPN_ID, PROFILE_VPN, TypedProfile.Type.Remote, url, content)
        select(profile, forceReload = true)
        return profile
    }

    private suspend fun writeProfile(
        context: Context,
        key: String,
        name: String,
        type: TypedProfile.Type,
        url: String?,
        content: String,
    ): Profile {
        var profile = findProfile(key, context)
        if (profile == null) {
            val typed = TypedProfile().apply {
                this.type = type
                if (url != null) {
                    remoteURL = url
                    autoUpdate = true
                    autoUpdateInterval = 60
                }
                lastUpdated = Date()
            }
            val fileID = ProfileManager.nextFileID()
            val dir = File(context.filesDir, "configs").also { it.mkdirs() }
            val file = File(dir, "$fileID.json")
            file.writeText(content)
            typed.path = file.path
            profile = ProfileManager.create(
                Profile(name = name, typed = typed).apply { userOrder = ProfileManager.nextOrder() },
            )
        } else {
            val file = File(profile.typed.path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            profile.name = name
            profile.typed.type = type
            if (url != null) {
                profile.typed.remoteURL = url
                profile.typed.autoUpdate = true
            }
            profile.typed.lastUpdated = Date()
            ProfileManager.update(profile)
        }
        prefs(context).edit().putLong(key, profile.id).apply()
        return profile
    }

    /** Делает профиль активным; если VPN уже запущен — перезагружает его с новой конфигурацией. */
    private fun select(profile: Profile, forceReload: Boolean = false) {
        val changed = Settings.selectedProfile != profile.id
        if (changed) Settings.selectedProfile = profile.id
        if (changed || forceReload) {
            runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
        }
    }
}
