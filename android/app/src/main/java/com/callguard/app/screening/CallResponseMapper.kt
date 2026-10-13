package com.callguard.app.screening

import android.telecom.CallScreeningService.CallResponse
import com.callguard.app.policy.Decision

object CallResponseMapper {
    fun map(decision: Decision): CallResponse = CallResponse.Builder()
        .setDisallowCall(decision == Decision.BLOCK)
        .setRejectCall(decision == Decision.BLOCK)
        .setSilenceCall(decision == Decision.SILENCE)
        .setSkipCallLog(false).setSkipNotification(false).build()
}
