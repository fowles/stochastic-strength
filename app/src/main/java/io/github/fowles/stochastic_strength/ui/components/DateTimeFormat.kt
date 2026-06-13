package io.github.fowles.stochastic_strength.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATETIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

internal fun formatDateTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DATETIME_FORMATTER)
