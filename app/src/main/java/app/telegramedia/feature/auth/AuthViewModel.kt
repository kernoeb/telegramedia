package app.telegramedia.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.telegramedia.telegram.AuthState
import app.telegramedia.telegram.TelegramService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(private val telegram: TelegramService) : ViewModel() {

    val authState: StateFlow<AuthState> = telegram.authState

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        telegram.start()
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    fun submitPhone(phone: String) = run { telegram.setPhoneNumber(phone) }
    fun submitCode(code: String) = run { telegram.checkCode(code) }
    fun submitPassword(password: String) = run { telegram.checkPassword(password) }
    fun logOut() = run { telegram.logOut() }
}
