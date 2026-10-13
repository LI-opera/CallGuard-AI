package com.callguard.app.screening

import com.callguard.app.policy.Decision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class CallResponseMapperTest {
    @Test fun mapsAllActionsWithoutHidingCallHistoryOrNotifications() {
        for (decision in Decision.entries) {
            val response=CallResponseMapper.map(decision)
            assertEquals(decision==Decision.BLOCK,response.disallowCall)
            assertEquals(decision==Decision.BLOCK,response.rejectCall)
            assertEquals(decision==Decision.SILENCE,response.silenceCall)
            assertFalse(response.skipCallLog);assertFalse(response.skipNotification)
        }
    }
}
