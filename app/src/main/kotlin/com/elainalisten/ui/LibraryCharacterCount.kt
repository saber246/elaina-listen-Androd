package com.elainalisten.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.elainalisten.R
import java.text.NumberFormat

@Composable
internal fun characterCountLabel(count: Long?): String = when {
    count == null -> stringResource(R.string.library_character_unknown)
    count < 10_000 -> stringResource(R.string.library_character_count, NumberFormat.getIntegerInstance().format(count))
    else -> {
        val format = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        stringResource(R.string.library_character_ten_thousands, format.format(count / 10_000.0))
    }
}
