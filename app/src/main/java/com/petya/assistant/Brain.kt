package com.petya.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.net.ssl.SSLException

data class BrainResult(val command: String?, val reply: String?)

/** Нейросеть GigaChat (Сбер): превращает любую фразу в команду или короткий ответ. */
object Brain {
    private const val AUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private const val CHAT_URL = "https://api.giga.chat/v1/chat/completions"

    @Volatile var lastError = ""
    private var token: String? = null
    private var tokenUntil = 0L

    private val SYSTEM = """
Ты разбираешь голосовые команды помощника для бегуна. Телефон в кармане, человек в наушниках.
Ответь ТОЛЬКО одним JSON-объектом, без пояснений и без markdown:
{"action":"...","name":"...","text":"...","app":"...","reply":"..."}
Допустимые значения action:
answer_call (ответить на звонок), end_call (сбросить звонок), call (позвонить; name — кому, как сказал человек),
message (написать сообщение в Телеграм; name — кому, text — текст от первого лица), next (следующий трек),
prev (предыдущий трек), pause (пауза), play (продолжить), volume_up (громче), volume_down (тише),
play_music (включить музыку), open_app (открыть приложение; app — название), time (сколько времени),
chat (вопрос или разговор: в reply короткий ответ по-русски, 1-2 предложения, без списков и без эмодзи),
none (команда непонятна).
Примеры:
"сделай потише пожалуйста" -> {"action":"volume_down"}
"поставь следующую" -> {"action":"next"}
"скинь любимой что скоро буду" -> {"action":"message","name":"любимой","text":"Скоро буду"}
"сколько калорий в банане" -> {"action":"chat","reply":"Примерно девяносто калорий."}
""".trimIndent()

    fun configured(ctx: Context) =
        !ctx.getSharedPreferences("p", 0).getString("gigaKey", "").isNullOrBlank()

    /** Блокирующий вызов — запускать не в главном потоке. */
    fun ask(ctx: Context, text: String): BrainResult? {
        lastError = ""
        return try {
            val key = ctx.getSharedPreferences("p", 0).getString("gigaKey", "")!!.trim()
            val tok = token(key)
            val body = JSONObject()
                .put("model", "GigaChat")
                .put("temperature", 0.1)
                .put("max_tokens", 200)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", text)))
                .toString()
            val resp = post(CHAT_URL, mapOf(
                "Content-Type" to "application/json", "Accept" to "application/json",
                "Authorization" to "Bearer $tok"), body)
            val content = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            parse(content)
        } catch (e: SSLException) {
            lastError = "Сертификат Минцифры не подключён: ${e.message}"; null
        } catch (e: Exception) {
            lastError = e.message ?: e.toString(); null
        }
    }

    private fun parse(content: String): BrainResult? {
        val a = content.indexOf('{'); val b = content.lastIndexOf('}')
        if (a < 0 || b <= a) { lastError = "Ответ не JSON: " + content.take(80); return null }
        val j = JSONObject(content.substring(a, b + 1))
        val name = j.optString("name").trim()
        val text = j.optString("text").trim()
        val app = j.optString("app").trim()
        val cmd: String? = when (j.optString("action")) {
            "answer_call" -> "ответь"
            "end_call" -> "сбрось"
            "call" -> if (name.isNotEmpty()) "позвони $name" else null
            "message" -> if (name.isNotEmpty() && text.isNotEmpty()) "напиши $name что $text" else null
            "next" -> "следующая песня"
            "prev" -> "предыдущая песня"
            "pause" -> "пауза"
            "play" -> "продолжи"
            "volume_up" -> "громче"
            "volume_down" -> "тише"
            "play_music" -> "включи музыку"
            "open_app" -> if (app.isNotEmpty()) "открой $app" else null
            "time" -> "который час"
            "chat" -> {
                val r = j.optString("reply").trim().take(300)
                return if (r.isNotEmpty()) BrainResult(null, r) else null
            }
            else -> null
        }
        return if (cmd != null) BrainResult(cmd.lowercase(), null) else null
    }

    private fun token(key: String): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenUntil) return it }
        val resp = post(AUTH_URL, mapOf(
            "Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json",
            "RqUID" to UUID.randomUUID().toString(), "Authorization" to "Basic $key"),
            "scope=GIGACHAT_API_PERS")
        val j = JSONObject(resp)
        val t = j.getString("access_token")
        token = t
        tokenUntil = if (j.has("expires_at")) j.getLong("expires_at") - 60_000 else now + 25 * 60_000
        return t
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000; c.readTimeout = 15000; c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IOException("HTTP $code ${txt.take(150)}")
        return txt
    }
}
