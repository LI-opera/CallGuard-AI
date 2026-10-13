package com.callguard.app.platform

import android.app.role.RoleManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallScreeningRoleGatewayTest {
    @Test fun unavailableRoleIsUnsupported() {
        val gateway = CallScreeningRoleGateway(RuntimeEnvironment.getApplication())
        assertEquals(RoleState.UNSUPPORTED, gateway.state())
    }

    @Test fun foregroundRefreshDetectsGrantAndRoleLoss() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(RoleManager::class.java)
        val shadow = shadowOf(manager)
        shadow.addAvailableRole(RoleManager.ROLE_CALL_SCREENING)
        val gateway = CallScreeningRoleGateway(context)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val observed = mutableListOf<RoleState>()
        gateway.observeRoleLoss(owner) { observed.add(it) }
        assertEquals(RoleState.NOT_HELD, observed.last())
        shadow.addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(RoleState.HELD, observed.last())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        shadow.removeHeldRole(RoleManager.ROLE_CALL_SCREENING)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(RoleState.NOT_HELD, observed.last())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
