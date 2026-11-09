package com.petya.assistant

import kotlinx.coroutines.flow.MutableStateFlow

object AssistantState {
    val running = MutableStateFlow(false)
    val active = MutableStateFlow(false)      // услышал «Петя», ждёт/выполняет команду
    val status = MutableStateFlow("Выключен")
    val heard = MutableStateFlow("")
    val action = MutableStateFlow("")
}
