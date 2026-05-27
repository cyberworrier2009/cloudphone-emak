package com.emaktalk.cloudphone.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emaktalk.cloudphone.sip.CallUiState
import com.emaktalk.cloudphone.ui.components.DialPad
import com.emaktalk.cloudphone.ui.theme.CallGreen
import com.emaktalk.cloudphone.ui.theme.HangupRed
import kotlinx.coroutines.delay
import org.linphone.core.Call

@Composable
fun InCallScreen(viewModel: InCallViewModel = viewModel()) {
    val call by viewModel.callState.collectAsState()
    val current = call ?: return

    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(current.isConnected) {
        if (current.isConnected) {
            while (true) {
                delay(1000)
                seconds++
            }
        }
    }

    var showKeypad by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = current.title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusLabel(current, seconds),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(visible = showKeypad && current.isConnected) {
                DialPad(
                    onKeyClick = { viewModel.sendDtmf(it) },
                    onZeroLongPress = { viewModel.sendDtmf('+') },
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            when {
                // Incoming and not yet answered: Answer / Decline.
                current.state == Call.State.IncomingReceived -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        RoundActionButton(
                            background = HangupRed,
                            icon = { Icon(Icons.Filled.CallEnd, "Decline", tint = Color.White) },
                            onClick = viewModel::hangUp
                        )
                        RoundActionButton(
                            background = CallGreen,
                            icon = { Icon(Icons.Filled.Call, "Answer", tint = Color.White) },
                            onClick = viewModel::answer
                        )
                    }
                }
                // Active / outgoing call: in-call controls + hang up.
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ToggleControl(
                            active = current.isMuted,
                            iconActive = { Icon(Icons.Filled.MicOff, "Unmute") },
                            iconInactive = { Icon(Icons.Filled.Mic, "Mute") },
                            label = "Mute",
                            onClick = { viewModel.toggleMute() }
                        )
                        ToggleControl(
                            active = showKeypad,
                            iconActive = { Icon(Icons.Filled.Dialpad, "Hide keypad") },
                            iconInactive = { Icon(Icons.Filled.Dialpad, "Keypad") },
                            label = "Keypad",
                            enabled = current.isConnected,
                            onClick = { showKeypad = !showKeypad }
                        )
                        ToggleControl(
                            active = current.isSpeakerOn,
                            iconActive = { Icon(Icons.Filled.VolumeUp, "Speaker off") },
                            iconInactive = { Icon(Icons.Filled.VolumeUp, "Speaker on") },
                            label = "Speaker",
                            onClick = { viewModel.toggleSpeaker() }
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    RoundActionButton(
                        background = HangupRed,
                        size = 72.dp,
                        icon = {
                            Icon(
                                Icons.Filled.CallEnd,
                                "Hang up",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        },
                        onClick = viewModel::hangUp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun statusLabel(call: CallUiState, seconds: Int): String = when {
    call.state == Call.State.IncomingReceived -> "Incoming call"
    call.isConnected -> formatDuration(seconds)
    call.isOutgoing -> "Calling…"
    else -> "Ringing…"
}

private fun formatDuration(seconds: Int): String {
    val mm = seconds / 60
    val ss = seconds % 60
    return "%02d:%02d".format(mm, ss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundActionButton(
    background: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    Surface(
        shape = CircleShape,
        color = background,
        onClick = onClick,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleControl(
    active: Boolean,
    iconActive: @Composable () -> Unit,
    iconInactive: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = if (enabled) container else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            contentColor = contentColor,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (active) iconActive() else iconInactive()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
