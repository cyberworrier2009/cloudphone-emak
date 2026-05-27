package com.emaktalk.cloudphone.ui.dialer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emaktalk.cloudphone.R
import com.emaktalk.cloudphone.ui.components.DialPad
import com.emaktalk.cloudphone.ui.theme.CallGreen
import org.linphone.core.RegistrationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(
    onOpenAccount: () -> Unit,
    viewModel: DialerViewModel = viewModel()
) {
    val number by viewModel.number.collectAsState()
    val registration by viewModel.registrationState.collectAsState()
    val callError by viewModel.callError.collectAsState()
    val context = LocalContext.current

    // Surface call failures (e.g. "Call failed: Forbidden") as a toast.
    LaunchedEffect(callError) {
        callError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearCallError()
        }
    }

    // A call needs the microphone; request it on demand if not yet granted.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onCall()
        } else {
            Toast.makeText(context, "Microphone permission is required to call", Toast.LENGTH_LONG)
                .show()
        }
    }

    val placeCall: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dialer_title)) },
                actions = {
                    RegistrationBadge(registration)
                    IconButton(onClick = onOpenAccount) {
                        Icon(Icons.Filled.Settings, contentDescription = "SIP account")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NumberDisplay(
                number = number,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            DialPad(
                onKeyClick = viewModel::onKeyPress,
                onZeroLongPress = viewModel::onZeroLongPress,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            CallActionRow(
                showBackspace = number.isNotEmpty(),
                onCall = placeCall,
                onBackspace = viewModel::onBackspace,
                onClear = viewModel::onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun NumberDisplay(number: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = number.ifEmpty { stringResource(R.string.dialer_hint) },
            fontSize = if (number.isEmpty()) 22.sp else 36.sp,
            color = if (number.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CallActionRow(
    showBackspace: Boolean,
    onCall: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Left spacer keeps the call button visually centered.
        Spacer(Modifier.size(64.dp))

        Surface(
            shape = CircleShape,
            color = CallGreen,
            onClick = onCall,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = stringResource(R.string.call),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center
        ) {
            if (showBackspace) {
                // Tap deletes one digit, long-press clears the whole number.
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = onBackspace,
                            onLongClick = onClear
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Backspace,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistrationBadge(state: RegistrationState) {
    val (color, label) = when (state) {
        RegistrationState.Ok -> CallGreen to "Online"
        RegistrationState.Progress -> MaterialTheme.colorScheme.tertiary to "Connecting"
        RegistrationState.Failed -> MaterialTheme.colorScheme.error to "Failed"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape) { Spacer(Modifier.size(10.dp)) }
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
    }
}
