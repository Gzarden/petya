package com.petya.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AssistantService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var awaitingUntil = 0L
    private lateinit var commands: CommandHandler

    private val idle = "Слушаю: скажите «Петя…»"
    private val wake = Regex("\\bпет[яьюи]\\b")
    private val listenRunnable = Runnable { listen() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        commands = CommandHandler(this)
        tts = TextToSpeech(this, this)
        startAsForeground()
        AssistantState.running.value = true
        setStatus(idle)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Нет службы распознавания речи (Google)"); return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val d = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { process(it) } ?: 250L
                    restart(d)
                }
                override fun onError(error: Int) =
                    restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 250)
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        listen()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); tts?.shutdown()
        AssistantState.running.value = false
        AssistantState.active.value = false
        setStatus("Выключен")
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        tts?.language = Locale("ru", "RU")
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { handler.post { speaking = false; restart(300) } }
            @Deprecated("") override fun onError(id: String?) { handler.post { speaking = false; restart(300) } }
        })
        ttsReady = true
    }

    private fun listen() {
        if (speaking) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { recognizer?.startListening(i) } catch (e: Exception) { restart(1000) }
    }

    private fun restart(delay: Long) {
        handler.removeCallbacks(listenRunnable)
        if (!speaking) handler.postDelayed(listenRunnable, delay)
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Возвращает паузу (мс) перед следующим прослушиванием. */
    private fun process(results: List<String>): Long {
        val texts = results.map(::norm)
        val now = SystemClock.elapsedRealtime()
        AssistantState.heard.value = texts.firstOrNull().orEmpty()

        var cmd: String? = null
        for (t in texts) {
            val m = wake.find(t)
            if (m != null) { cmd = t.substring(m.range.last + 1).trim(); break }
        }
        if (cmd == null && now < awaitingUntil) cmd = texts.firstOrNull()
        if (cmd == null) return 250

        if (cmd.isBlank()) {               // сказали только «Петя» -> сигнал, ждём команду
            awaitingUntil = now + 6000
            AssistantState.active.value = true
            setStatus("Слушаю команду…")
            beep(ToneGenerator.TONE_PROP_BEEP)
            handler.postDelayed({
                if (SystemClock.elapsedRealtime() >= awaitingUntil) { AssistantState.active.value = false; setStatus(idle) }
            }, 6100)
            return 700
        }
        awaitingUntil = 0
        val res = commands.handle(cmd)
        AssistantState.action.value = res.say
        AssistantState.active.value = false
        setStatus(idle)
        beep(if (res.ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK)
        if (getSharedPreferences("p", 0).getBoolean("voice", false)) speak(res.say)
        return 700
    }

    private fun beep(type: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(type, 200)
            handler.postDelayed({ tg.release() }, 600)
        } catch (e: Exception) { }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        speaking = true
        recognizer?.cancel()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
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
