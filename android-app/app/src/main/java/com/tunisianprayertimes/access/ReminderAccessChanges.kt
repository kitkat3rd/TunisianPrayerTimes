package com.tunisianprayertimes.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A foreground access snapshot needs refreshing when a reminder starts or stops using an Android permission. */
internal object ReminderAccessChanges {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun changed() = changes.update { it + 1 }
}
