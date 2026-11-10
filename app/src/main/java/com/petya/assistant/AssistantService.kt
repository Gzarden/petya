package com.petya.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Слушает микрофон через Vosk (офлайн).
 *  - cmdRec: узкая грамматика («Петя», «громче», «следующая песня»…) — очень надёжно.
 *  - freeRec: обычное распознавание — для имён, приложений и текста сообщений.
 */
class AssistantService : Service() {
    companion object {
        val FIXED = listOf(
            "ответь", "ответить на звонок", "возьми трубку", "сбрось", "положи трубку",
            "следующая песня", "следующая", "следующий трек", "дальше",
            "предыдущая песня", "предыдущая", "пауза", "стоп", "продолжи",
            "тише", "потише", "громче", "погромче",
            "включи музыку", "выключи музыку", "который час", "сколько времени"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var cmdRec: Recognizer? = null
    private var freeRec: Recognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var alive = false
    @Volatile private var destroyed = false
    @Volatile private var muteUntil = 0L
    private var awaitingUntil = 0L
    private lateinit var commands: CommandHandler

    private val idle = "Слушаю: скажите «Петя…»"
    private val wakeRe = Regex("\\bпет[яьюие]\\b")

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        commands = CommandHandler(this)
        startAsForeground()
        tts = TextToSpeech(this) { st -> handler.post { initTts(st) } }
        AssistantState.running.value = true
        setStatus("Загружаю модель речи… (первый раз до минуты)")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "petya:listen").apply {
            setReferenceCounted(false); acquire()
        }
        StorageService.unpack(this, "model", "model",
            { m: Model -> handler.post { startEngine(m) } },
            { e: IOException -> handler.post { setStatus("Ошибка модели: ${e.message}") } })
    }

    override fun onDestroy() {
        destroyed = true
        tts?.shutdown()
        alive = false                      // аудио-поток сам закроет распознаватели
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        AssistantState.running.value = false
        AssistantState.active.value = false
        setStatus("Выключен")
        super.onDestroy()
    }

    private fun startEngine(m: Model) {
        if (destroyed) { m.close(); return }
        model = m
        try {
            val grammar = JSONArray(FIXED + FIXED.map { "петя $it" } + listOf("петя", "[unk]")).toString()
            cmdRec = Recognizer(m, 16000f, grammar)
            freeRec = Recognizer(m, 16000f)
        } catch (e: Exception) { setStatus("Ошибка распознавателя: ${e.message}"); return }
        alive = true
        setStatus(idle)
        Thread({ audioLoop() }, "petya-audio").start()
    }

    private fun audioLoop() {
        val g = cmdRec ?: return
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 4
            )
        } catch (e: Exception) { handler.post { setStatus("Нет доступа к микрофону") }; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { handler.post { setStatus("Микрофон недоступен") }; return }

        val buf = ShortArray(2048)
        val utter = ArrayList<ShortArray>()
        rec.startRecording()
        while (alive) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) { Thread.sleep(20); continue }
            if (SystemClock.elapsedRealtime() < muteUntil) { utter.clear(); continue }
            utter.add(buf.copyOf(n))
            if (g.acceptWaveForm(buf, n)) {
                val text = JSONObject(g.result).optString("text")
                val chunks = ArrayList<ShortArray>(utter); utter.clear()
                if (text.isNotBlank()) handler.post { onUtterance(text, chunks) }
            } else {
                // в тишине держим только ~1.3 c «разбега», при речи копим всю фразу (до ~11 c)
                val silent = utter.size > 10 && JSONObject(g.partialResult).optString("partial").isEmpty()
                if (silent || utter.size > 90) utter.removeAt(0)
            }
        }
        rec.stop(); rec.release()
        g.close()
        exec.execute { freeRec?.close(); freeRec = null; model?.close(); model = null }
        exec.shutdown()
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Конец фразы по узкой грамматике. */
    private fun onUtterance(raw: String, chunks: List<ShortArray>) {
        val now = SystemClock.elapsedRealtime()
        val hasUnk = raw.contains("[unk]")
        val a = norm(raw.replace("[unk]", " "))
        if (a.isNotEmpty()) AssistantState.heard.value = a

        if (!hasUnk) {                                  // быстрый путь: чистая команда
            val m = wakeRe.find(a)
            val body = if (m != null) a.substring(m.range.last + 1).trim() else if (now < awaitingUntil) a else null
            if (body != null) {
                if (body.isEmpty()) { onWakeOnly(now); return }
                if (body in FIXED) { execute(body); return }
            }
        }
        // не уложилось в грамматику — полное распознавание той же фразы
        exec.execute {
            val r = freeRec
            var f = ""
            if (r != null && !destroyed) {
                r.reset()
                for (c in chunks) r.acceptWaveForm(c, c.size)
                f = norm(JSONObject(r.finalResult).optString("text"))
            }
            handler.post { onFree(a, f) }
        }
    }

    private fun onFree(a: String, f: String) {
        val now = SystemClock.elapsedRealtime()
        if (f.isNotEmpty()) AssistantState.heard.value = f
        val mf = wakeRe.find(f)
        val ma = wakeRe.find(a)
        val cmd: String? = when {
            mf != null -> f.substring(mf.range.last + 1).trim()
            ma != null -> if (f.isNotEmpty()) f else a.substring(ma.range.last + 1).trim()
            now < awaitingUntil -> f.ifEmpty { a }
            else -> null
        }
        if (cmd == null) return
        if (cmd.isEmpty()) { onWakeOnly(now); return }
        execute(cmd)
    }

    private fun onWakeOnly(now: Long) {
        awaitingUntil = now + 6000
        AssistantState.active.value = true
        setStatus("Слушаю команду…")
        beep(ToneGenerator.TONE_PROP_BEEP)
        handler.postDelayed({
            if (SystemClock.elapsedRealtime() >= awaitingUntil) { AssistantState.active.value = false; setStatus(idle) }
        }, 6100)
    }

    private fun execute(cmd: String) {
        awaitingUntil = 0
        val res = commands.handle(cmd)
        if (!res.ok && res.say == "Не понял" && Brain.configured(this)) { askBrain(cmd); return }
        finish(cmd, res)
    }

    /** Фраза не подошла ни одной команде — отдаём нейросети. */
    private fun askBrain(cmd: String) {
        AssistantState.action.value = "«$cmd» → думаю…"
        setStatus("Думаю…")
        Thread {
            val r = Brain.ask(this, cmd)
            handler.post {
                setStatus(idle)
                if (r == null && Brain.lastError.isNotEmpty()) AssistantState.screen.value = "Нейросеть: ${Brain.lastError}"
                val res = when {
                    r == null -> Res("Не понял", false)
                    r.reply != null -> Res(r.reply, true, true, true)
                    r.command != null -> commands.handle(r.command)
                    else -> Res("Не понял", false)
                }
                finish(cmd, res)
            }
        }.start()
    }

    private fun finish(cmd: String, res: Res) {
        AssistantState.action.value = "«$cmd» → ${res.say}"
        AssistantState.active.value = false
        setStatus(idle)
        val voice = getSharedPreferences("p", 0).getBoolean("voice", true)
        if ((voice || res.force) && ttsReady && res.speak && res.say.isNotBlank()) speak(res.say)
        else beep(if (res.ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK)
    }

    private fun initTts(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        t.language = Locale("ru", "RU")
        val name = getSharedPreferences("p", 0).getString("voiceName", null)
        if (name != null) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
        t.setPitch(0.9f)
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)   // музыка приглушается
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { muteUntil = SystemClock.elapsedRealtime() + 400 }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { muteUntil = SystemClock.elapsedRealtime() + 400 }
        })
        ttsReady = true
    }

    private fun speak(text: String) {
        muteUntil = SystemClock.elapsedRealtime() + 400 + text.length * 90L   // не слушаем самих себя
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun beep(type: Int) {
        muteUntil = SystemClock.elapsedRealtime() + 800     // чтобы не услышать свой сигнал
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(type, 200)
            handler.postDelayed({ tg.release() }, 600)
        } catch (e: Exception) { }
    }

    private fun setStatus(s: String) { AssistantState.status.value = s }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("petya", "Петя", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "petya")
            .setContentTitle("Петя слушает")
            .setContentText("Скажите: «Петя, следующая песня»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
