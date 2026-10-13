package com.callguard.app.ui.home
import com.callguard.app.data.AppDatabase
import com.callguard.app.data.dao.DaySummary
import java.time.*
/** Reads only persisted submissions, not observed ringing or completed conversations. */
class HomeViewModel : androidx.lifecycle.ViewModel() {
    fun load(db:AppDatabase, now:ZonedDateTime=ZonedDateTime.now()):DaySummary {
        val day=now.toLocalDate()
        return db.callRecords().summary(day.atStartOfDay(now.zone).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli())
    }
}
