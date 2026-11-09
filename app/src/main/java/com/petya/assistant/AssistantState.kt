package com.petya.assistant

import kotlinx.coroutines.flow.MutableStateFlow

object AssistantState {
    val running = MutableStateFlow(false)
    val active = MutableStateFlow(false)
    val status = MutableStateFlow("Выключен")
    val heard = MutableStateFlow("")
    val action = MutableStateFlow("")
    val screen = MutableStateFlow("")   // список кнопок на экране (для отладки)
}
