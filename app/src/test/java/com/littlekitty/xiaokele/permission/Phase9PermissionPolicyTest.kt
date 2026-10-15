package com.littlekitty.xiaokele.permission

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9PermissionPolicyTest {

    @Test
    fun android12And13RequestCoarsePlusFineLocation() {
        val permissions = Phase9PermissionPolicy.permissionsToRequest(sdkInt = 31).toSet()
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)

        val api33 = Phase9PermissionPolicy.permissionsToRequest(sdkInt = 33).toSet()
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in api33)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in api33)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in api33)
    }

    @Test
    fun android14And15KeepSameForegroundOnlyFlow() {
        val api34 = Phase9PermissionPolicy.permissionsToRequest(sdkInt = 34).toSet()
        val api35 = Phase9PermissionPolicy.permissionsToRequest(sdkInt = 35).toSet()
        assertEquals(api34, api35)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in api34)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in api34)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in api34)
    }

    @Test
    fun backgroundLocationIsNotPartOfThePublicPermissionPolicy() {
        assertFalse(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION in
                Phase9PermissionPolicy.permissionsToRequest(sdkInt = 35),
        )
    }
}
