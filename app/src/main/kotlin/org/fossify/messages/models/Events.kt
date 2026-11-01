package org.fossify.messages.models

class Events {
    class VerificationCodeReceived(val code: String)

    class RefreshMessages
    class RefreshConversations
    class RefreshDashboard
    class RefreshSendHistory
}
