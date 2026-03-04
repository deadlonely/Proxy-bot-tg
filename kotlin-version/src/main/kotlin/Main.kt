import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ProxyEntry(val region: String, val type: String, val proxy: String, val comment: String?)

val mapper = ObjectMapper().registerKotlinModule()
val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
var offset = 0L

// runtime maps
val userState = mutableMapOf<Long, MutableMap<String, Any?>>()
val userLang = mutableMapOf<Long, String>()
// editing by users removed for safety

// proxy status persistence
val proxyStatus = mutableMapOf<String, MutableMap<String, Any?>>()
val statusFile = Paths.get("..", "db", "proxy_status.json").toFile()

fun ensureStatusDir() { try { statusFile.parentFile.mkdirs() } catch (_: Exception) {} }

fun loadProxyStatus() {
    try {
        if (!statusFile.exists()) return
        val txt = statusFile.readText()
        if (txt.isBlank()) return
        val m = mapper.readValue(txt, Map::class.java) as Map<String, Map<String, Any?>>
        for ((k,v) in m) proxyStatus[k] = v.toMutableMap()
    } catch (e: Exception) { println("Failed loading proxy status: ${e.message}") }
}

fun saveProxyStatus() {
    try { ensureStatusDir(); statusFile.writeText(mapper.writeValueAsString(proxyStatus)) } catch (e: Exception) { println("Failed saving proxy status: ${e.message}") }
}

object AppInit {
    init {
        Thread {
            while (true) {
                try { Thread.sleep(60_000); saveProxyStatus() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        loadProxyStatus()
    }
}

// country detection cache
val ipCountryCache = mutableMapOf<String, String>()
fun detectCountryCodeForHost(host: String?): String {
    if (host == null) return "AUTO"
    if (ipCountryCache.containsKey(host)) return ipCountryCache[host]!!
    try {
        // try resolve hostname to IP first (like Node dns.lookup)
        var ip = host
        try {
            val inet = java.net.InetAddress.getByName(host)
            ip = inet.hostAddress
        } catch (_: Exception) { ip = host }
        val url = "http://ip-api.com/json/${ip}?fields=countryCode"
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val parsed = try { mapper.readValue(resp.body(), Map::class.java) as Map<String, Any?> } catch (_: Exception) { null }
        val code = (parsed?.get("countryCode") as? String) ?: "AUTO"
        ipCountryCache[host] = code
        return code
    } catch (e: Exception) { return "AUTO" }
}

fun parseProxiesGroupedByIp(file: File): Map<String, List<ProxyEntry>> {
    val regions = mutableMapOf<String, MutableList<ProxyEntry>>()
    if (!file.exists()) return regions
    val lines = file.readLines()
    for (raw in lines) {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split('|').map { it.trim() }
        var type = "UNKNOWN"
        var proxy = ""
        if (parts.size == 3) { type = parts[1]; proxy = parts[2] }
        else if (parts.size == 2) { proxy = parts[1] }
        else proxy = parts[0]
        var comment: String? = null
        if (proxy.contains(' ')) {
            val toks = proxy.split(Regex("\\s+"))
            proxy = toks[0]
            comment = toks.drop(1).joinToString(" ")
        }
        val addr = if (proxy.contains('@')) proxy.substringAfter('@') else proxy
        val host = addr.substringBefore(':')
        val country = detectCountryCodeForHost(host)
        val entry = ProxyEntry(country, type, proxy, comment)
        regions.computeIfAbsent(country) { mutableListOf() }.add(entry)
    }
    return regions
}

fun parseProxies(file: File): List<ProxyEntry> {
    if (!file.exists()) return emptyList()
    val out = mutableListOf<ProxyEntry>()
    for (raw in file.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split('|').map { it.trim() }
        var region = "AUTO"
        var type = "UNKNOWN"
        var proxy = ""
        if (parts.size == 3) { region = if (parts[0].isEmpty()) "AUTO" else parts[0]; type = parts[1]; proxy = parts[2] }
        else if (parts.size == 2) { region = if (parts[0].isEmpty()) "AUTO" else parts[0]; proxy = parts[1] }
        else proxy = parts[0]
        var comment: String? = null
        if (proxy.contains(' ')) {
            val toks = proxy.split(Regex("\\s+"))
            proxy = toks[0]
            comment = toks.drop(1).joinToString(" ")
        }
        out.add(ProxyEntry(region, type, proxy, comment))
    }
    return out
}

fun measureProxyLatency(proxy: String, timeoutMs: Int = 5000): Pair<Boolean, Long?> {
    var addr = proxy
    if (proxy.contains('@')) addr = proxy.substringAfter('@')
    if (!addr.contains(':')) return Pair(false, null)
    val host = addr.substringBefore(':')
    val port = addr.substringAfter(':').toIntOrNull() ?: return Pair(false, null)
    val socket = Socket()
    val start = System.currentTimeMillis()
    return try {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        val ms = System.currentTimeMillis() - start
        socket.close()
        Pair(true, ms)
    } catch (e: Exception) {
        try { socket.close() } catch (_: Exception) {}
        Pair(false, null)
    }
}

fun buildInlineKeyboard(rows: List<List<Map<String, Any>>>): Map<String, Any> {
    return mapOf("inline_keyboard" to rows.map { row -> row.map { it } })
}

fun countryCodeToFlag(cc: String?): String {
    if (cc == null) return "🌐"
    if (cc == "AUTO" || cc == "unknown") return "🌐"
    val c = cc.uppercase()
    if (c.length != 2) return "🏳️"
    return try { val points = c.map { 0x1F1E6 + it.code - 65 }; String(Character.toChars(points[0])) + String(Character.toChars(points[1])) } catch (_: Exception) { "🏳️" }
}

fun buildRegionPayload(region: String, entries: List<ProxyEntry>, opts: Map<String, Any?> = emptyMap(), lang: String = "ru"): Pair<String, List<List<Map<String, Any>>>> {
    val provider = System.getenv("PROVIDER_NAME") ?: System.getenv("CHANNEL_NAME") ?: "NEXORA"
    val updatedAt = try { java.time.ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")).toString() } catch (_: Exception) { LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) }
    val sb = StringBuilder()
    sb.append("━━━━━━━━━━\n")
    sb.append("🌋 $provider - $region PROXIES\n")
    sb.append(if (lang=="ru") "Всего: ${entries.size}\n" else "Total: ${entries.size}\n")
    sb.append(if (lang=="ru") "Обновлено: $updatedAt\n" else "Updated: $updatedAt\n")
    sb.append("━━━━━━━━━━\n\n")
    val kb = mutableListOf<List<Map<String, Any>>>()
    val checkedResults = opts["checkedResults"] as? Map<Int, Boolean> ?: emptyMap()
    val checkedIndex = opts["checkedIndex"] as? Int
    for (i in entries.indices) {
        val e = entries[i]
        val st = proxyStatus[e.proxy]
        val notChecked = if (lang=="ru") "⏳ Не проверено" else "⏳ Not checked"
        val active = if (lang=="ru") "✅ Активный" else "✅ Active"
        val inactive = if (lang=="ru") "❌ Не активен" else "❌ Inactive"
        val checkedSuffix = if (lang=="ru") " (Проверен)" else " (Checked)"
        var status = notChecked
        if (st != null) {
            val alive = st["alive"] as? Boolean ?: false
            val ms = st["ms"]
            status = if (alive) {
                if (ms != null) "$active — $ms ms" else active
            } else inactive
            val at = st["at"] as? String
            if (at != null) {
                try {
                    val t = java.time.Instant.parse(at).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                    status += " ($t)"
                } catch (_: Exception) {}
            }
        }
        if (checkedResults.containsKey(i)) status = if (checkedResults[i] == true) active else inactive
        if (checkedIndex != null && checkedIndex == i) status += checkedSuffix
        val line1 = "#${i+1} ${e.type.uppercase()} ${countryCodeToFlag(region)} $status"
        sb.append(line1).append("\n")
        sb.append(e.proxy).append("\n")
        if (!e.comment.isNullOrEmpty()) sb.append("${e.comment}\n")
        sb.append("\n")
        kb.add(listOf(mapOf("text" to "📱 QR ${i+1}", "callback_data" to "qr_${region}_$i"), mapOf("text" to "🔗 ${i+1}", "callback_data" to "connect_${region}_$i"), mapOf("text" to if (lang=="ru") "🔄 Проверить" else "🔄 Check", "callback_data" to "check_${region}_$i")))
    }
    // refresh for this region
    kb.add(listOf(mapOf("text" to "🔁 Обновить список", "callback_data" to "refresh_$region")))
    kb.add(listOf(mapOf("text" to "⬅️ Назад к регионам", "callback_data" to "open_proxies"), mapOf("text" to "⬅️ Главное меню", "callback_data" to "main_menu")))
    return Pair(sb.toString(), kb)
}

fun sendApi(method: String, body: Any?): String? {
    val token = System.getenv("BOT_TOKEN") ?: System.getenv("TELEGRAM_BOT_TOKEN") ?: return null
    val url = "https://api.telegram.org/bot$token/$method"
    val json = if (body == null) "" else mapper.writeValueAsString(body)
    val req = if (body == null) HttpRequest.newBuilder(URI.create(url)).GET().build()
    else HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    return resp.body()
}

fun sendMessage(chatId: Long, text: String, replyMarkup: Any? = null): String? {
    val body = mutableMapOf<String, Any>("chat_id" to chatId, "text" to text, "parse_mode" to "Markdown")
    if (replyMarkup != null) body["reply_markup"] = replyMarkup
    return sendApi("sendMessage", body)
}

fun editOrReplyForUser(uid: Long, chatIdCtx: Long, messageIdCtx: Int, text: String, kb: Any?): Boolean {
    val last = userState[uid]?.get("lastList") as? Map<String, Any?>
    val replyMarkup = kb
    if (last != null) {
        val lc = (last["chat"] as? Number)?.toLong()
        val lm = (last["message_id"] as? Number)?.toInt()
        if (lc != null && lm != null) {
            try { editMessage(lc, lm, text, replyMarkup); return true } catch (_: Exception) {}
        }
    }
    // try to edit the current callback message
    try { editMessage(chatIdCtx, messageIdCtx, text, replyMarkup); return true } catch (_: Exception) {}
    // fallback: send new message and store as lastList
    val resp = sendMessage(chatIdCtx, text, replyMarkup)
    val parsed = try { if (resp != null) mapper.readValue(resp, Map::class.java) as Map<String, Any?> else null } catch (_: Exception) { null }
    val resultMsg = parsed?.get("result") as? Map<String, Any?>
    if (resultMsg != null) {
        val sentChat = (resultMsg["chat"] as? Map<String, Any?>)?.get("id") as? Number
        val sentId = resultMsg["message_id"] as? Number
        if (sentChat != null && sentId != null) {
            userState[uid] = userState[uid] ?: mutableMapOf(); userState[uid]?.put("lastList", mapOf("chat" to sentChat.toLong(), "message_id" to sentId.toInt()))
        }
    }
    return false
}

fun sendPhotoUrl(chatId: Long, photoUrl: String, caption: String? = null): String? {
    val body = mutableMapOf<String, Any>("chat_id" to chatId, "photo" to photoUrl)
    if (caption != null) body["caption"] = caption
    return sendApi("sendPhoto", body)
}

fun editMessage(chatId: Long, messageId: Int, text: String, replyMarkup: Any? = null) {
    val body = mutableMapOf<String, Any>("chat_id" to chatId, "message_id" to messageId, "text" to text, "parse_mode" to "Markdown")
    if (replyMarkup != null) body["reply_markup"] = replyMarkup
    sendApi("editMessageText", body)
}

fun answerCallback(callbackId: String, text: String?) {
    val body = mutableMapOf<String, Any>("callback_query_id" to callbackId)
    if (text != null) body["text"] = text
    sendApi("answerCallbackQuery", body)
}

fun getUpdates(): List<Map<String, Any>> {
    val body = mapOf("offset" to offset, "timeout" to 10)
    val s = sendApi("getUpdates", body) ?: return emptyList()
    val parsed = try { mapper.readValue(s, Map::class.java) as Map<String, Any> } catch (e: Exception) { return emptyList() }
    val ok = parsed["ok"] as? Boolean ?: false
    if (!ok) return emptyList()
    val result = parsed["result"] as? List<Map<String, Any>> ?: emptyList()
    return result
}

fun generateSocksQR(proxy: String): Pair<String, String>? {
    var user = ""; var pass: String? = null; var addr = proxy
    if (proxy.contains('@')) {
        val p = proxy.split('@', limit = 2)
        val creds = p[0]; addr = p[1]
        if (creds.contains(':')) { val cc = creds.split(':', limit = 2); user = cc[0]; pass = cc[1] } else user = creds
    }
    if (!addr.contains(':')) return null
    val host = addr.substringBefore(':')
    val port = addr.substringAfter(':')
    val link = "tg://socks?server=${java.net.URLEncoder.encode(host, "UTF-8")}&port=${port}${if (user.isNotEmpty()) "&user=${java.net.URLEncoder.encode(user, "UTF-8")}${if (!pass.isNullOrEmpty()) "&pass=${java.net.URLEncoder.encode(pass, "UTF-8")}" else "" }" else "" }"
    val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=${java.net.URLEncoder.encode(link, "UTF-8")}" 
    return Pair(link, qrUrl)
}

fun runBot() {
    val proxiesFile = Paths.get("proxies.txt").toFile()
    val rootCopy = Paths.get("..", "proxies.txt").toFile()
    if (!proxiesFile.exists() && rootCopy.exists()) try { rootCopy.copyTo(proxiesFile); println("Copied proxies.txt from root") } catch (_: Exception) {}
    if (!proxiesFile.exists()) println("Warning: proxies.txt not found")
    println("Bot starting polling...")
    while (true) {
        try {
            val updates = getUpdates()
            for (u in updates) {
                val uId = (u["update_id"] as Number).toLong()
                offset = uId + 1
                if (u.containsKey("message")) {
                    val msg = u["message"] as Map<String, Any>
                    val text = msg["text"] as? String ?: ""
                    val chat = msg["chat"] as Map<String, Any>
                    val chatId = (chat["id"] as Number).toLong()
                    val msgId = (msg["message_id"] as Number).toInt()
                    val from = msg["from"] as? Map<String, Any>
                    val fromId = (from?.get("id") as? Number)?.toLong() ?: chatId

                    // user edit flow disabled — ignore plain text messages here

                    if (text.startsWith("/start")) {
                        val kb = buildInlineKeyboard(listOf(listOf(mapOf("text" to "RU", "callback_data" to "lang_ru"), mapOf("text" to "EN", "callback_data" to "lang_en"))))
                        sendMessage(chatId, "*Выберите язык / Choose language*", kb)
                    }
                }

                if (u.containsKey("callback_query")) {
                    val cq = u["callback_query"] as Map<String, Any>
                    val data = cq["data"] as? String ?: ""
                    val from = cq["from"] as Map<String, Any>
                    val fromId = (from["id"] as Number).toLong()
                    val callbackId = cq["id"] as String
                    val message = cq["message"] as? Map<String, Any>
                    val chat = message?.get("chat") as? Map<String, Any>
                    val chatId = (chat?.get("id") as? Number)?.toLong() ?: fromId
                    val messageId = (message?.get("message_id") as? Number)?.toInt() ?: 0
                    when {
                        data.startsWith("lang_") -> {
                            val lang = data.substringAfter("lang_")
                            userLang[fromId] = lang
                            answerCallback(callbackId, if (lang=="ru") "Язык выбран: Русский" else "Language set: English")
                            val provider = System.getenv("PROVIDER_NAME") ?: "Nexora"
                            val text = if (lang=="ru") "🌋 $provider - Proxy\n\n📄 Описание\nПремиум-прокси с глобальным покрытием\n🚀 Быстрый · Надёжный · Анонимный\n\n☰ Главное меню" else "🌋 $provider - Proxy\n\n📄 Description\nPremium proxy service with global coverage\n🚀 Fast · Reliable · Anonymous\n\n☰ Main Menu"
                            val kbRows = mutableListOf<List<Map<String, Any>>>()
                            kbRows.add(listOf(mapOf("text" to (if (lang=="ru") "🔗 Канал" else "🔗 Channel"), "url" to (System.getenv("CHANNEL_URL") ?: "https://t.me/nexoraproxy"))))
                            kbRows.add(listOf(mapOf("text" to (if (lang=="ru") "🗂️ Прокси" else "🗂️ Proxies"), "callback_data" to "open_proxies")))
                            kbRows.add(listOf(mapOf("text" to (if (lang=="ru") "📱 Помощь (QR)" else "📱 QR Help"), "callback_data" to "qr_help")))
                            val kb = buildInlineKeyboard(kbRows)
                            try { editOrReplyForUser(fromId, chatId, messageId, text, kb) } catch (e: Exception) { try { editMessage(chatId, messageId, text, kb) } catch (_: Exception) { sendMessage(chatId, text, kb) } }
                        }
                        data == "open_proxies" -> {
                            val lang = userLang[fromId] ?: "ru"
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val regions = regionsMap.keys.toList()
                            val total = regionsMap.values.fold(0) { acc, l -> acc + l.size }
                            if (regions.isEmpty()) { sendMessage(chatId, if (lang=="ru") "Нет доступных прокси. Поместите список в proxies.txt" else "No proxies available. Put list in proxies.txt") ; continue }
                            val rows = regions.map { r -> listOf(mapOf("text" to "${countryCodeToFlag(r)} $r (${regionsMap[r]?.size ?: 0})", "callback_data" to "region_$r")) }
                            val kb = buildInlineKeyboard(rows + listOf(listOf(mapOf("text" to "🔁 Обновить список", "callback_data" to "refresh_all"))) + listOf(listOf(mapOf("text" to "⬅️ Назад", "callback_data" to "main_menu"))))
                            val text = if (lang=="ru") "Выберите регион — Всего: $total" else "Choose region — Total: $total"
                            val resp = sendMessage(chatId, text, kb)
                            val parsed = try { if (resp != null) mapper.readValue(resp, Map::class.java) as Map<String, Any?> else null } catch (_: Exception) { null }
                            val resultMsg = parsed?.get("result") as? Map<String, Any?>
                            if (resultMsg != null) {
                                val sentChat = (resultMsg["chat"] as? Map<String, Any?>)?.get("id") as? Number
                                val sentId = resultMsg["message_id"] as? Number
                                if (sentChat != null && sentId != null) userState[fromId] = userState[fromId] ?: mutableMapOf<String, Any?>().apply { put("lastList", mapOf("chat" to sentChat.toLong(), "message_id" to sentId.toInt())) }
                            }
                        }
                        data == "refresh_all" -> {
                            val lang = userLang[fromId] ?: "ru"
                            answerCallback(callbackId, if (lang=="ru") "Обновлено" else "Updated")
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val regions = regionsMap.keys.toList()
                            val total = regionsMap.values.fold(0) { acc, l -> acc + l.size }
                            if (regions.isEmpty()) { sendMessage(chatId, if (lang=="ru") "Нет доступных прокси. Поместите список в proxies.txt" else "No proxies available. Put list in proxies.txt") ; continue }
                            val rows = regions.map { r -> listOf(mapOf("text" to "${countryCodeToFlag(r)} $r (${regionsMap[r]?.size ?: 0})", "callback_data" to "region_$r")) }
                            val kb = buildInlineKeyboard(rows + listOf(listOf(mapOf("text" to "🔁 Обновить список", "callback_data" to "refresh_all"))) + listOf(listOf(mapOf("text" to "⬅️ Назад", "callback_data" to "main_menu"))))
                            // try edit lastList
                            val last = userState[fromId]?.get("lastList") as? Map<String, Any?>
                            val text = if (lang=="ru") "Обновлено — Всего прокси: $total" else "Updated — Total proxies: $total"
                            if (last != null) {
                                val lc = (last["chat"] as? Number)?.toLong()
                                val lm = (last["message_id"] as? Number)?.toInt()
                                if (lc != null && lm != null) {
                                    try { editMessage(lc, lm, text, kb); continue } catch (_: Exception) {}
                                }
                            }
                            try { sendMessage(chatId, text, kb) } catch (_: Exception) {}
                        }
                        data.startsWith("refresh_") -> {
                            // refresh a single region view
                            val region = data.substringAfter("refresh_")
                            val lang = userLang[fromId] ?: "ru"
                            answerCallback(callbackId, if (lang=="ru") "Обновлено" else "Updated")
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val entries = regionsMap[region] ?: emptyList()
                            if (entries.isEmpty()) { sendMessage(chatId, if (lang=="ru") "Нет прокси в этом регионе" else "No proxies in this region") ; continue }
                            val (text, kbList) = buildRegionPayload(region, entries, emptyMap(), lang)
                            val last = userState[fromId]?.get("lastList") as? Map<String, Any?>
                            if (last != null) {
                                val lc = (last["chat"] as? Number)?.toLong()
                                val lm = (last["message_id"] as? Number)?.toInt()
                                if (lc != null && lm != null) {
                                    try { editMessage(lc, lm, text, buildInlineKeyboard(kbList)); continue } catch (_: Exception) {}
                                }
                            }
                            try { sendMessage(chatId, text, buildInlineKeyboard(kbList)) } catch (_: Exception) {}
                        }
                        data == "main_menu" -> {
                            answerCallback(callbackId, null)
                            val lang = userLang[fromId] ?: "ru"
                            val provider = System.getenv("PROVIDER_NAME") ?: "Nexora"
                            val text = if (lang=="ru") "🌋 $provider - Proxy\n\n📄 Описание\nПремиум-прокси с глобальным покрытием\n🚀 Быстрый · Надёжный · Анонимный\n\n☰ Главное меню" else "🌋 $provider - Proxy\n\n📄 Description\nPremium proxy service with global coverage\n🚀 Fast · Reliable · Anonymous\n\n☰ Main Menu"
                            val kbRows = mutableListOf<List<Map<String, Any>>>()
                            kbRows.add(listOf(mapOf("text" to (if (lang=="ru") "🔗 Канал" else "🔗 Channel"), "url" to (System.getenv("CHANNEL_URL") ?: "https://t.me/nexoraproxy"))))
                            kbRows.add(listOf(mapOf("text" to (if (lang=="ru") "🗂️ Прокси" else "🗂️ Proxies"), "callback_data" to "open_proxies")))
                            kbRows.add(listOf(mapOf("text" to (if (lang=="ru") "📱 Помощь (QR)" else "📱 QR Help"), "callback_data" to "qr_help")))
                            val kb = buildInlineKeyboard(kbRows)
                            try { editOrReplyForUser(fromId, chatId, messageId, text, kb) } catch (_: Exception) { try { sendMessage(chatId, text, kb) } catch (_: Exception){} }
                        }
                        data.startsWith("region_") -> {
                            val lang = userLang[fromId] ?: "ru"
                            val region = data.substringAfter("region_")
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val entries = regionsMap[region] ?: emptyList()
                            if (entries.isEmpty()) { sendMessage(chatId, if (lang=="ru") "Нет прокси в этом регионе" else "No proxies in this region") ; continue }
                            val (text, kbList) = buildRegionPayload(region, entries, emptyMap(), lang)
                            val last = userState[fromId]?.get("lastList") as? Map<String, Any?>
                            if (last != null) {
                                val lc = (last["chat"] as? Number)?.toLong()
                                val lm = (last["message_id"] as? Number)?.toInt()
                                if (lc != null && lm != null) {
                                    try { editMessage(lc, lm, text, buildInlineKeyboard(kbList)); continue } catch (_: Exception) {}
                                }
                            }
                            try { sendMessage(chatId, text, buildInlineKeyboard(kbList)) } catch (_: Exception) {}
                        }
                        data.startsWith("check_") -> {
                            val parts = data.split('_', limit = 3)
                            val region = parts[1]; val idx = parts[2].toIntOrNull() ?: 0
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val entries = regionsMap[region] ?: emptyList()
                            if (idx >= entries.size) { answerCallback(callbackId, if ((userLang[fromId] ?: "ru")=="ru") "Прокси не найдено" else "Proxy not found") ; continue }
                            answerCallback(callbackId, if ((userLang[fromId] ?: "ru")=="ru") "Проверяю..." else "Checking...")
                            val (alive, ms) = measureProxyLatency(entries[idx].proxy, 5000)
                            proxyStatus[entries[idx].proxy] = mutableMapOf("alive" to alive, "ms" to ms, "at" to Instant.now().toString())
                            val checkedResults = mapOf(idx to alive)
                            val lang = userLang[fromId] ?: "ru"
                            val (txt, kbList) = buildRegionPayload(region, entries, mapOf("checkedIndex" to idx, "checkedResults" to checkedResults), lang)
                            try { editMessage(chatId, messageId, txt, buildInlineKeyboard(kbList)) } catch (_: Exception) { sendMessage(chatId, txt, buildInlineKeyboard(kbList)) }
                            val msg = if (alive) (if (lang=="ru") "✅ Проверен — ${ms ?: ""} ms" else "✅ Checked — ${ms ?: ""} ms") else if (lang=="ru") "❌ Не активен" else "❌ Inactive"
                            answerCallback(callbackId, msg)
                        }
                        data == "qr_help" -> {
                            answerCallback(callbackId, null)
                            val lang = userLang[fromId] ?: "ru"
                            val textRu = "✨ *Как работает QR-подключение* ✨\n\n🔹 *Что это делает*\nQR содержит все параметры прокси (сервер, порт, логин) для быстрого импорта в Telegram.\n\n🔹 *Как пользоваться*\n1️⃣ Откройте Telegram (моб.) → Настройки → Данные и диск\n2️⃣ Раздел «Прокси» → Добавить прокси\n3️⃣ Сканируйте QR или нажмите *Быстрое подключение*\n\n⏳ *Важно:* QR действителен 5 минут — откройте заново, если истёк."
                            val textEn = "✨ *QR Connection — Quick Guide* ✨\n\n🔹 *What it does*\nQR encodes all proxy parameters (server, port, user) for quick import into Telegram."
                            val kb = buildInlineKeyboard(listOf(listOf(mapOf("text" to if (lang=="ru") "⬅️ Назад" else "⬅️ Back", "callback_data" to "main_menu"))))
                            try { sendMessage(chatId, if (lang=="ru") textRu else textEn, kb) } catch (_: Exception) {}
                        }
                        data.startsWith("qr_") -> {
                            val parts = data.split('_', limit = 3)
                            val region = parts[1]; val idx = parts[2].toIntOrNull() ?: 0
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val entries = regionsMap[region] ?: emptyList()
                            if (idx >= entries.size) { answerCallback(callbackId, if ((userLang[fromId]?:"ru")=="ru") "Прокси не найдено" else "Proxy not found") ; continue }
                            val qr = generateSocksQR(entries[idx].proxy)
                            if (qr == null) { answerCallback(callbackId, if ((userLang[fromId]?:"ru")=="ru") "Ошибка генерации QR" else "QR generation error"); continue }
                            val (link, qrUrl) = qr
                            val lang = userLang[fromId] ?: "ru"
                            val caption = if (lang=="ru") "📱 QR-Код для подключения\n\n🌐 Регион: $region ${countryCodeToFlag(region)}\n📊 Тип: ${entries[idx].type.uppercase()}\n🖥️ Сервер: ${entries[idx].proxy.substringAfter('@').substringBefore(':')}:${entries[idx].proxy.substringAfter(':')}\n\n⏳ Действует: 5 минут" else "📱 QR for connection"
                            sendPhotoUrl(chatId, qrUrl, caption)
                            answerCallback(callbackId, if (lang=="ru") "QR отправлен" else "QR sent")
                        }
                        data.startsWith("connect_") -> {
                            val parts = data.split('_', limit = 3)
                            val region = parts[1]; val idx = parts[2].toIntOrNull() ?: 0
                            val regionsMap = parseProxiesGroupedByIp(proxiesFile)
                            val entries = regionsMap[region] ?: emptyList()
                            if (idx >= entries.size) { answerCallback(callbackId, if ((userLang[fromId]?:"ru")=="ru") "Прокси не найдено" else "Proxy not found") ; continue }
                            val entry = entries[idx]
                            val link = generateSocksQR(entry.proxy)?.first ?: ""
                            val lang = userLang[fromId] ?: "ru"
                            val hostPart = try { entry.proxy.substringAfter('@').substringBefore(':') } catch (_: Exception) { entry.proxy }
                            val text = if (lang=="ru") "📡 *Подключение к прокси*\n\n🌐 Region: $region ${countryCodeToFlag(region)}\n🖥️ Server: <code>$hostPart</code>\n\nНажмите кнопку ниже, чтобы подключиться:" else "📡 *Proxy connection*"
                            val kb = buildInlineKeyboard(listOf(listOf(mapOf("text" to (if (lang=="ru") "🔗 Подключиться" else "🔗 Connect"), "url" to link)), listOf(mapOf("text" to if (lang=="ru") "⬅️ Назад" else "⬅️ Back", "callback_data" to "region_$region"))))
                            try { editOrReplyForUser(fromId, chatId, messageId, text, kb) } catch (_: Exception) { try { sendMessage(chatId, text, kb) } catch (_: Exception){} }
                        }
                        // edit action removed
                    }
                }
            }
        } catch (e: Exception) {
            println("Poll error: ${e.message}")
            Thread.sleep(1000)
        }
    }
}

fun main() { runBot() }

