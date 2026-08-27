package org.fossify.messages.models

/**
 * Trigger type that initiated an SMS send operation.
 * Used by the shadow observability layer to categorize sends.
 */
enum class SmsSendTriggerType {
    THREAD,
    NEW_CONVERSATION,
    BULK,
    SCHEDULED_ALARM,
    SCHEDULED_SEND_NOW,
    DIRECT_REPLY,
    HEADLESS,
    SMS_DIRECT_TEST,
    FORWARDING_SMS_DIRECT,
    REMOTE_SMS_COMMAND,
    REMOTE_DINGTALK_COMMAND,
    UI,
    RECOVERY,
    /** Used when the send chain is instrumented but the caller's trigger is unknown (legacy code paths). */
    LEGACY_UNKNOWN
}

/**
 * Lifecycle state of an SMS send operation.
 */
enum class SmsSendState {
    PENDING,
    SUBMITTING,
    SUBMITTED,
    SENT,
    DELIVERED,
    FAILED,
    UNKNOWN_AFTER_SUBMIT
}

/**
 * Delivery state of an SMS send operation (Carrier SMSC delivery to recipient device).
 */
enum class SmsSendDeliveryState {
    NOT_REQUESTED,
    WAITING,
    DELIVERED,
    DELIVERY_FAILED
}

/**
 * State of an individual SMS part in multipart transmission.
 */
enum class SmsSendPartState {
    PENDING,
    SUBMITTED,
    SENT,
    FAILED,
    NOT_REQUESTED,
    WAITING,
    DELIVERED,
    DELIVERY_FAILED
}

/**
 * Context captured at the start of a send operation, passed from the call site
 * to the shadow coordinator. Contains only data needed for observability;
 * address and body are never stored in plaintext.
 */
data class SmsSendContext(
    val triggerType: SmsSendTriggerType,
    val address: String?,
    val body: String?,
    val subscriptionId: Int,
    val threadId: Long?,
    val requireDeliveryReport: Boolean,
    val messageUri: String?
)
