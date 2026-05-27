package com.emaktalk.cloudphone.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A single dialpad key (the primary digit plus the letters underneath it). */
private data class DialKey(val digit: Char, val letters: String)

private val DIAL_KEYS = listOf(
    DialKey('1', ""),
    DialKey('2', "ABC"),
    DialKey('3', "DEF"),
    DialKey('4', "GHI"),
    DialKey('5', "JKL"),
    DialKey('6', "MNO"),
    DialKey('7', "PQRS"),
    DialKey('8', "TUV"),
    DialKey('9', "WXYZ"),
    DialKey('*', ""),
    DialKey('0', "+"),
    DialKey('#', "")
)

/**
 * Classic 4x3 softphone keypad.
 *
 * @param onKeyClick invoked with the pressed character ('0'-'9', '*', '#').
 * @param onZeroLongPress invoked when '0' is long-pressed (inserts '+').
 */
@Composable
fun DialPad(
    onKeyClick: (Char) -> Unit,
    onZeroLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DIAL_KEYS.chunked(3).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowKeys.forEach { key ->
                    DialButton(
                        key = key,
                        onClick = { onKeyClick(key.digit) },
                        onLongClick = if (key.digit == '0') onZeroLongPress else null,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialButton(
    key: DialKey,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = key.digit.toString(),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (key.letters.isNotEmpty()) {
                    Text(
                        text = key.letters,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
