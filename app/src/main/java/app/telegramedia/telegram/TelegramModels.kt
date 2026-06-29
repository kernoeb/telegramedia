package app.telegramedia.telegram

/**
 * High-level authorization state, intentionally mirroring TDLib's
 * `AuthorizationState*` hierarchy so the real TDLib-backed implementation
 * can map onto it 1:1 later.
 */
sealed interface AuthState {
    /** TDLib is still initializing / setting parameters. */
    data object Initializing : AuthState

    /** Waiting for the user to enter a phone number. */
    data object WaitPhoneNumber : AuthState

    /** Waiting for the SMS / app login code. */
    data class WaitCode(val phoneNumber: String) : AuthState

    /** Waiting for the 2FA cloud password. */
    data class WaitPassword(val hint: String?) : AuthState

    /** Fully authorized and ready to use. */
    data object Ready : AuthState

    /** Logged out / closed. */
    data object LoggedOut : AuthState

    data class Error(val message: String) : AuthState
}

enum class ChatKind { PRIVATE, GROUP, CHANNEL, BOT }

data class TgChat(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val lastMessage: String?,
    val unreadCount: Int,
    /** Local file path or remote URL for the chat photo; null if none. */
    val photoUrl: String?,
    /** TDLib file id of the chat's small photo, or null. */
    val photoFileId: Int? = null,
    /** True when this chat is known to contain video/media worth a library view. */
    val hasMedia: Boolean,
)
