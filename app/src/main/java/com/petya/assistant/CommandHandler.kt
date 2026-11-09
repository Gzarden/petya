package com.petya.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Разбирает текст команды и выполняет действие. Возвращает фразу для озвучки. */
class CommandHandler(private val ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val handler = Handler(Looper.getMainLooper())

    // Музыкальное приложение по умолчанию (Яндекс.Музыка)
    private val musicPackage = "ru.yandex.music"

    // Свои слова для приложений: «Петя, включи антирадар» -> ищем в названиях приложений
    private val aliases = mapOf(
        "антирадар" to listOf("антирадар", "радар", "radar"),
        "навигатор" to listOf("навигатор", "navigator"),
        "карты" to listOf("карты", "maps"),
        "такси" to listOf("такси", "taxi"),
    )

    fun handle(cmd: String): String {
        fun has(vararg w: String) = w.any { cmd.contains(it) }
        return when {
            has("ответь", "ответить", "возьми трубку", "подними трубку", "прими") -> answerCall()
            has("сбрось", "положи трубку", "отбой", "заверши звонок") -> endCall()
            has("позвони", "набери") -> call(cmd.substringAfter("позвони", cmd.substringAfter("набери", "")).trim())
            has("следующ", "дальше", "пропусти", "переключи") -> key(KeyEvent.KEYCODE_MEDIA_NEXT, "Следующий трек")
            has("предыдущ", "прошлый", "прошлая", "назад") -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Предыдущий трек")
            has("пауза", "стоп", "останови", "выключи музык") -> key(KeyEvent.KEYCODE_MEDIA_PAUSE, "Пауза")
            has("продолжи", "играй") -> key(KeyEvent.KEYCODE_MEDIA_PLAY, "Продолжаю")
            has("тише", "потише", "убавь") -> volume(AudioManager.ADJUST_LOWER, "Тише")
            has("громче", "погромче", "добавь") -> volume(AudioManager.ADJUST_RAISE, "Громче")
            has("включи музык", "музыку", "музыка") -> playMusic()
            has("время", "сколько времени", "который час") ->
                "Сейчас " + SimpleDateFormat("H:mm", Locale("ru")).format(Date())
            has("включи", "открой", "запусти", "поехали") -> openApp(
                cmd.replace(Regex("^.*?(включи|открой|запусти|поехали)\\s*"), "").trim()
            )
            else -> "Не понял"
        }
    }

    private fun answerCall(): String = try {
        telecom.acceptRingingCall(); "Отвечаю"
    } catch (e: SecurityException) { "Нет разрешения на звонки" }

    @Suppress("DEPRECATION")
    private fun endCall(): String = try {
        telecom.endCall(); "Сбросил"
    } catch (e: SecurityException) { "Нет разрешения на звонки" }

    private fun call(spoken: String): String {
        if (spoken.isBlank()) return "Кому звонить?"
        val num = findNumber(spoken) ?: return "Не нашёл контакт $spoken"
        return try {
            telecom.placeCall(Uri.fromParts("tel", num.second, null), Bundle())
            "Звоню: ${num.first}"
        } catch (e: SecurityException) { "Нет разрешения на звонки" }
    }

    /** Сопоставление с учётом падежей: «любимой» ~ «Любимая». */
    private fun similar(a: String, b: String): Boolean {
        val p = a.zip(b).takeWhile { it.first == it.second }.size
        return p >= 3 && p >= minOf(a.length, b.length) - 2
    }

    private fun findNumber(spoken: String): Pair<String, String>? {
        val target = spoken.lowercase().replace('ё', 'е').trim()
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val n = name.lowercase().replace('ё', 'е')
                    if (n == target || n.split(" ").any { w -> target.split(" ").any { t -> similar(w, t) } })
                        return name to c.getString(1)
                }
            }
        } catch (e: SecurityException) { }
        return null
    }

    private fun send(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun key(code: Int, reply: String): String { send(code); return reply }

    private fun volume(dir: Int, reply: String): String {
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        return reply
    }

    private fun playMusic(): String {
        if (audio.isMusicActive) return "Музыка уже играет"
        send(KeyEvent.KEYCODE_MEDIA_PLAY)
        handler.postDelayed({
            if (!audio.isMusicActive) {          // приложение не запущено — открываем
                launch(musicPackage)
                handler.postDelayed({ send(KeyEvent.KEYCODE_MEDIA_PLAY) }, 3000)
            }
        }, 2500)
        return "Включаю музыку"
    }

    private fun launch(pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); return true
    }

    private fun openApp(query: String): String {
        if (query.isBlank()) return "Что включить?"
        val words = aliases.entries.firstOrNull { query.contains(it.key.dropLast(2)) }?.value
            ?: listOf(if (query.length > 5) query.dropLast(2) else query)
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val hit = apps.firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase().replace('ё', 'е')
            words.any { label.contains(it) }
        } ?: return "Не нашёл приложение $query"
        launch(hit.activityInfo.packageName)
        return "Открываю ${hit.loadLabel(pm)}"
    }
}
