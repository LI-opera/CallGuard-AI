package com.callguard.app.platform

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

enum class RoleState { UNSUPPORTED, NOT_HELD, HELD }

class CallScreeningRoleGateway(context: Context) {
    private val manager = context.getSystemService(RoleManager::class.java)
    fun isHeld(): Boolean = isAvailable() && manager!!.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    fun isAvailable(): Boolean = manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
    fun state(): RoleState = when {
        !isAvailable() -> RoleState.UNSUPPORTED
        isHeld() -> RoleState.HELD
        else -> RoleState.NOT_HELD
    }

    fun requestRole(launcher: ActivityResultLauncher<Intent>): Boolean {
        if (!isAvailable()) return false
        return try {
            launcher.launch(manager!!.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            true
        } catch (_: ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }

    /** Refresh on foreground entry, including returning from system settings.
     * Does not claim background delivery of role changes. */
    fun observeRoleLoss(owner: LifecycleOwner, onState: (RoleState) -> Unit) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { onState(state()) }
        })
        onState(state())
    }
}
