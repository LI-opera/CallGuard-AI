package com.callguard.app.data.dao
import androidx.room.*
import com.callguard.app.data.entity.CallerProfileEntity
@Dao interface CallerProfileDao {
    @Query("SELECT * FROM caller_profiles WHERE numberToken = :token") fun get(token: String): CallerProfileEntity?
    @Query("UPDATE caller_profiles SET userLabel=:label, profileVersion=profileVersion+1 WHERE numberToken=:token AND (userLabel IS NULL OR userLabel!=:label)")
    fun setLabel(token:String,label:String):Int
    @Query("SELECT COUNT(*) FROM caller_profiles") fun count(): Int
}
