package com.petya.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Res(val say: String, val ok: Boolean = true, val speak: Boolean = true, val force: Boolean = false)

/** Разбирает текст команды и выполняет действие. */
class CommandHandler(private val ctx: Context) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val handler = Handler(Looper.getMainLooper())

    private val musicPackage = ClickService.MUSIC

    // «Петя, включи антирадар» -> ищем в названиях приложений любое из слов
    private val aliases = mapOf(
        "радар" to listOf("антирадар", "радар", "radar"),
        "навигатор" to listOf("навигатор", "navigator"),
        "карт" to listOf("карты", "maps"),
        "такси" to listOf("такси", "taxi"),
    )
    // Для этих приложений после запуска нажимается «Старт», если ещё не запущено
    private val startLabels = listOf("старт", "start", "запустить", "начать", "включить")
    private val stopLabels = listOf("стоп", "stop", "остановить", "выключить")

    fun handle(cmd: String): Res {
        fun has(vararg w: String) = w.any { cmd.contains(it) }
        return when {
            cmd.startsWith("напиш") || cmd.startsWith("отправ") -> message(cmd)
            has("на экране", "покажи кнопки") -> screen()
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
                Res("Сейчас " + SimpleDateFormat("H:mm", Locale("ru")).format(Date()))
            has("включи", "открой", "запусти", "поехали") -> openApp(
                cmd.replace(Regex("^.*?(включи|открой|запусти|поехали)\\s*"), "").trim()
            )
            else -> Res("Не понял", false)
        }
    }

    // ---------- звонки ----------
    private fun answerCall(): Res = try {
        telecom.acceptRingingCall(); Res("Отвечаю")
    } catch (e: SecurityException) { Res("Нет разрешения на звонки", false) }

    @Suppress("DEPRECATION")
    private fun endCall(): Res = try {
        telecom.endCall(); Res("Сбросил")
    } catch (e: SecurityException) { Res("Нет разрешения на звонки", false) }

    private fun call(spoken: String): Res {
        if (spoken.isBlank()) return Res("Кому звонить?", false)
        val c = findContact(spoken) ?: return Res("Не нашёл контакт $spoken", false)
        return try {
            telecom.placeCall(Uri.fromParts("tel", c.second, null), Bundle())
            Res("Звоню: ${c.first}")
        } catch (e: SecurityException) { Res("Нет разрешения на звонки", false) }
    }

    /** Сопоставление с учётом падежей: «любимой» ~ «Любимая». */
    private fun similar(a: String, b: String): Boolean {
        val p = a.zip(b).takeWhile { it.first == it.second }.size
        return p >= 3 && p >= minOf(a.length, b.length) - 2
    }

    private fun findContact(spoken: String): Pair<String, String>? {
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

    // ---------- Телеграм ----------
    private fun message(cmd: String): Res {
        val rest = cmd.replace(Regex("^(напиш\\S*|отправ\\S*)\\s*(сообщение)?\\s*"), "")
        val idx = rest.indexOf(" что ")
        val name = if (idx > 0) rest.substring(0, idx) else rest.substringBefore(" ")
        val msg = (if (idx > 0) rest.substring(idx + 5) else rest.substringAfter(" ", ""))
            .trim().replaceFirstChar { it.uppercase() }
        if (name.isBlank() || msg.isBlank()) return Res("Что написать?", false)
        val c = findContact(name) ?: return Res("Не нашёл контакт $name", false)

        var d = c.second.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("8")) d = "7" + d.drop(1)
        val uri = Uri.parse("tg://resolve?phone=$d&text=${Uri.encode(msg)}")
        wake()
        var opened = false
        for (p in listOf("org.telegram.messenger", "org.telegram.messenger.web")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                opened = true; break
            } catch (e: Exception) { }
        }
        if (!opened) return Res("Телеграм не найден", false)
        val svc = ClickService.instance ?: return Res("Включите спец. возможности", false)
        svc.clickRetry(listOf("отправить", "send"), tries = 12, delay = 2500)
        return Res("Пишу: ${c.first}")
    }

    // ---------- медиа ----------
    private fun send(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun key(code: Int, reply: String): Res { send(code); return Res(reply) }

    private fun volume(dir: Int, reply: String): Res {
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        return Res(reply)
    }

    /** Нажатие медиа-кнопки прямо в приложение (так «просыпается» остановленный плеер). */
    private fun sendTo(pkg: String, code: Int) {
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            try {
                ctx.sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(a, code))
                )
            } catch (e: Exception) { }
        }
    }

    private fun playMusic(): Res {
        if (audio.isMusicActive) return Res("Музыка уже играет")
        send(KeyEvent.KEYCODE_MEDIA_PLAY)                                   // 1) общая кнопка
        handler.postDelayed({
            if (!audio.isMusicActive) {
                sendTo(musicPackage, KeyEvent.KEYCODE_MEDIA_PLAY)           // 2) кнопка прямо в Яндекс.Музыку
                handler.postDelayed({
                    if (!audio.isMusicActive) {                             // 3) открыть и нажать Play
                        wake(); launch(musicPackage); ClickService.instance?.autoPlay()
                    }
                }, 1800)
            }
        }, 1200)
        return if (ClickService.instance == null) Res("Включите спец. возможности", false) else Res("Включаю музыку", true, false)
    }

    // ---------- приложения ----------
    @Suppress("DEPRECATION")
    private fun wake() {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "petya:wake")
            .acquire(20_000)
    }

    private fun launch(pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); return true
    }

    private fun openApp(query: String): Res {
        if (query.isBlank()) return Res("Что включить?", false)
        val q = query.replace(" ", "")
        val words = aliases.entries.firstOrNull { q.contains(it.key) }?.value
            ?: listOf(if (query.length > 5) query.dropLast(2) else query)
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase().replace('ё', 'е')
            words.any { label.contains(it) }
        } ?: return Res("Не нашёл приложение $query", false)

        val pkg = hit.activityInfo.packageName
        if (pkg == musicPackage) return playMusic()
        wake()
        launch(pkg)
        if (q.contains("радар")) {
            ClickService.instance?.clickRetry(startLabels, tries = 10, stop = stopLabels, delay = 2000)
        }
        return Res("Открываю ${hit.loadLabel(pm)}")
    }

    private fun screen(): Res {
        val svc = ClickService.instance ?: return Res("Включите спец. возможности", false)
        AssistantState.screen.value = svc.dump()
        return Res("Список сохранён")
    }
}
