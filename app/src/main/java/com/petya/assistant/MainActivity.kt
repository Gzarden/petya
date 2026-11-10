package com.petya.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

private val perms = buildList {
    add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE); add(Manifest.permission.ANSWER_PHONE_CALLS)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun App() {
    val ctx = LocalContext.current
    val running by AssistantState.running.collectAsState()
    val active by AssistantState.active.collectAsState()
    val status by AssistantState.status.collectAsState()
    val heard by AssistantState.heard.collectAsState()
    val action by AssistantState.action.collectAsState()

    val screen by AssistantState.screen.collectAsState()
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var accOk by remember { mutableStateOf(ClickService.instance != null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { overlayOk = Settings.canDrawOverlays(ctx); accOk = ClickService.instance != null }
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }

    fun startSvc() = ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java))
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.RECORD_AUDIO] == true) startSvc()
    }

    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        0.92f, if (active) 1.2f else 1.07f,
        infiniteRepeatable(tween(if (active) 650 else 2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val base = when {
        !running -> Color(0xFF475569)
        active -> Color(0xFF34D399)
        else -> Color(0xFF7C5CFF)
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF151B34))))
            .verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Петя", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("голосовой помощник для бега", color = Color(0xFF94A3B8), fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        Canvas(Modifier.size(240.dp)) {
            val r = size.minDimension / 2 * 0.55f * (if (running) pulse else 1f)
            drawCircle(Brush.radialGradient(listOf(base.copy(alpha = .35f), Color.Transparent)), radius = r * 1.9f)
            drawCircle(Brush.radialGradient(listOf(base.copy(alpha = .95f), base.copy(alpha = .5f))), radius = r)
        }

        Text(status, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (heard.isNotBlank()) Text("«$heard»", color = Color(0xFF94A3B8), fontSize = 14.sp)
        if (action.isNotBlank()) Text("→ $action", color = Color(0xFF34D399), fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (running) ctx.stopService(Intent(ctx, AssistantService::class.java))
                else launcher.launch(perms)
            },
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF334155) else Color(0xFF7C5CFF))
        ) { Text(if (running) "Остановить" else "Запустить", fontSize = 17.sp) }

        Spacer(Modifier.height(16.dp))
        if (!overlayOk) {
            OutlinedButton(
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Разрешить «поверх других окон» (чтобы открывать приложения)") }
        }
        OutlinedButton(
            onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Не усыплять в фоне (батарея)") }

        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (accOk) "Спец. возможности: включены ✓" else "Включить спец. возможности (нажатие кнопок)") }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x22FFFFFF)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Команды", color = Color.White, fontWeight = FontWeight.SemiBold)
            listOf(
                "Петя, ответь · сбрось", "Петя, позвони Любимой", "Петя, следующая песня · предыдущая",
                "Петя, тише · громче · пауза", "Петя, включи музыку", "Петя, включи антирадар / навигатор",
                "Петя, напиши Любимой, что бегу домой (Телеграм)", "Петя, который час", "Петя, что на экране (список кнопок)"
            ).forEach { Text(it, color = Color(0xFFCBD5E1), fontSize = 13.sp) }
        }
        if (screen.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x22FFFFFF)).padding(16.dp)) {
                Text("Что было на экране", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(screen, color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
    }
}
