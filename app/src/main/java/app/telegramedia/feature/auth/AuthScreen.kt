package app.telegramedia.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.telegramedia.telegram.AuthState
import app.telegramedia.ui.theme.Ink
import app.telegramedia.ui.theme.Teal
import app.telegramedia.ui.theme.TextSecondary
import app.telegramedia.ui.theme.Violet
import org.koin.androidx.compose.koinViewModel

private enum class AuthStep { PHONE, CODE, PASSWORD }

@Composable
fun AuthScreen(viewModel: AuthViewModel = koinViewModel()) {
    val state by viewModel.authState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Ink,
                    0.5f to Ink,
                    1f to Violet.copy(alpha = 0.12f),
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brandmark()
            Spacer(Modifier.height(48.dp))

            val error = (state as? AuthState.Error)?.message
            var lastInputStep by remember { mutableStateOf(AuthStep.PHONE) }
            var lastPhone by remember { mutableStateOf("") }
            var lastHint by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(state) {
                (state as? AuthState.WaitCode)?.let { lastPhone = it.phoneNumber }
                (state as? AuthState.WaitPassword)?.let { lastHint = it.hint }
            }

            // Resolve which step to show. On Error/Ready we hold the last input step
            // so a wrong code/password keeps the user there (with the error) instead
            // of bouncing back to phone entry.
            val step = when (state) {
                is AuthState.WaitCode -> AuthStep.CODE
                is AuthState.WaitPassword -> AuthStep.PASSWORD
                is AuthState.Error, is AuthState.Ready -> lastInputStep
                else -> AuthStep.PHONE
            }
            LaunchedEffect(step) { lastInputStep = step }

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "authStep",
            ) { s ->
                when (s) {
                    AuthStep.PHONE -> PhoneStep(busy, error = error) { viewModel.submitPhone(it) }
                    AuthStep.CODE -> CodeStep(lastPhone, busy, error = error) { viewModel.submitCode(it) }
                    AuthStep.PASSWORD -> PasswordStep(lastHint, busy, error = error) { viewModel.submitPassword(it) }
                }
            }
        }
    }
}

@Composable
private fun Brandmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Violet, Teal))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Telegramedia", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your Telegram, as a cinema.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Composable
private fun PhoneStep(busy: Boolean, error: String? = null, onSubmit: (String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    StepScaffold(
        title = "Enter your phone number",
        subtitle = "We'll send a login code to your Telegram app.",
        error = error,
    ) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue", busy, enabled = phone.length >= 4) { onSubmit(phone) }
    }
}

@Composable
private fun CodeStep(phone: String, busy: Boolean, error: String? = null, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    StepScaffold(
        title = "Enter the code",
        subtitle = if (phone.isNotBlank()) "Sent to $phone  ·  try 12345" else "Sent to your Telegram app  ·  try 12345",
        error = error,
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Login code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Verify", busy, enabled = code.length >= 5) { onSubmit(code) }
    }
}

@Composable
private fun PasswordStep(hint: String?, busy: Boolean, error: String? = null, onSubmit: (String) -> Unit) {
    var pw by rememberSaveable { mutableStateOf("") }
    StepScaffold(
        title = "Two-step verification",
        subtitle = hint?.let { "Hint: $it  ·  try hunter2" } ?: "Enter your cloud password.",
        error = error,
    ) {
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("Password") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Unlock", busy, enabled = pw.isNotEmpty()) { onSubmit(pw) }
    }
}

@Composable
private fun StepScaffold(
    title: String,
    subtitle: String,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        content()
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Violet),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = Ink,
            )
        } else {
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
}
