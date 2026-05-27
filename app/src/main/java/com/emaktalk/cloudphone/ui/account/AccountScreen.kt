package com.emaktalk.cloudphone.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emaktalk.cloudphone.R
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = viewModel()
) {
    val registration by viewModel.registrationState.collectAsState()
    val registrationMessage by viewModel.registrationMessage.collectAsState()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(TransportType.Tls) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusLine(registration, registrationMessage)

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.account_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.account_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text(stringResource(R.string.account_domain)) },
                singleLine = true,
                placeholder = { Text("sip.example.com") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("Transport", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TransportType.Udp, TransportType.Tcp, TransportType.Tls)
                    .forEach { option ->
                        FilterChip(
                            selected = transport == option,
                            onClick = { transport = option },
                            label = { Text(option.name.uppercase()) }
                        )
                    }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.register(username, password, domain, transport) },
                enabled = username.isNotBlank() && domain.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.account_register))
            }

            OutlinedButton(
                onClick = viewModel::unregister,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.account_unregister))
            }
        }
    }
}

@Composable
private fun StatusLine(state: RegistrationState, message: String) {
    val base = when (state) {
        RegistrationState.Ok -> "Registered — ready to call"
        RegistrationState.Progress -> "Registering…"
        RegistrationState.Failed -> "Registration failed — check credentials"
        RegistrationState.Cleared -> "Signed out"
        else -> "Not registered"
    }
    val text = if (state == RegistrationState.Failed && message.isNotBlank()) {
        "$base ($message)"
    } else {
        base
    }
    val color = when (state) {
        RegistrationState.Ok -> MaterialTheme.colorScheme.tertiary
        RegistrationState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
}
