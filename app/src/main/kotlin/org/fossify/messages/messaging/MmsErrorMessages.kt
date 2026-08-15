package org.fossify.messages.messaging

import android.content.Context
import android.telephony.SmsManager
import org.fossify.messages.R

fun Context.getMmsSendErrorMessage(resultCode: Int): String {
    return when (resultCode) {
        SmsManager.MMS_ERROR_INVALID_APN -> getString(R.string.mms_error_invalid_apn)
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> getString(R.string.mms_error_unable_connect)
        SmsManager.MMS_ERROR_HTTP_FAILURE -> getString(R.string.mms_error_http_failure)
        SmsManager.MMS_ERROR_IO_ERROR -> getString(R.string.mms_error_io_error)
        SmsManager.MMS_ERROR_RETRY -> getString(R.string.mms_error_retry)
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> getString(R.string.mms_error_configuration)
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> getString(R.string.mms_error_no_data_network)
        SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID -> getString(R.string.mms_error_invalid_subscription)
        SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION -> getString(R.string.mms_error_inactive_subscription)
        SmsManager.MMS_ERROR_DATA_DISABLED -> getString(R.string.mms_error_data_disabled)
        else -> getString(R.string.unknown_error_occurred_sending_message, resultCode)
    }
}
