package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHealthTest {
    @Test
    fun `notification permission is not required before Android 13 when notifications are enabled`() {
        assertEquals(
            PermissionStatus.NotRequired,
            notificationPermissionStatus(
                sdkInt = 32,
                permissionGranted = false,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun `disabled app notifications are unhealthy on every supported Android version`() {
        assertEquals(
            PermissionStatus.Denied,
            notificationPermissionStatus(
                sdkInt = 32,
                permissionGranted = true,
                notificationsEnabled = false,
            ),
        )
        assertEquals(
            PermissionStatus.Denied,
            notificationPermissionStatus(
                sdkInt = 35,
                permissionGranted = true,
                notificationsEnabled = false,
            ),
        )
    }

    @Test
    fun `Android 13 notification health follows the runtime grant`() {
        assertEquals(
            PermissionStatus.Denied,
            notificationPermissionStatus(
                sdkInt = 33,
                permissionGranted = false,
                notificationsEnabled = true,
            ),
        )
        assertEquals(
            PermissionStatus.Granted,
            notificationPermissionStatus(
                sdkInt = 33,
                permissionGranted = true,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun `automatic notification request occurs once only when it can help`() {
        assertTrue(
            shouldAutomaticallyRequestNotificationPermission(
                hasNotificationSubscriptions = true,
                notificationStatus = PermissionStatus.Denied,
                canRequestPermission = true,
                hasRequestedAutomatically = false,
            )
        )
        assertFalse(
            shouldAutomaticallyRequestNotificationPermission(
                hasNotificationSubscriptions = true,
                notificationStatus = PermissionStatus.Denied,
                canRequestPermission = true,
                hasRequestedAutomatically = true,
            )
        )
        assertFalse(
            shouldAutomaticallyRequestNotificationPermission(
                hasNotificationSubscriptions = true,
                notificationStatus = PermissionStatus.Denied,
                canRequestPermission = false,
                hasRequestedAutomatically = false,
            )
        )
        assertFalse(
            shouldAutomaticallyRequestNotificationPermission(
                hasNotificationSubscriptions = false,
                notificationStatus = PermissionStatus.Denied,
                canRequestPermission = true,
                hasRequestedAutomatically = false,
            )
        )
        assertFalse(
            shouldAutomaticallyRequestNotificationPermission(
                hasNotificationSubscriptions = true,
                notificationStatus = PermissionStatus.Granted,
                canRequestPermission = true,
                hasRequestedAutomatically = false,
            )
        )
    }

    @Test
    fun `overlay health follows the special access grant`() {
        assertEquals(PermissionStatus.Granted, overlayPermissionStatus(permissionGranted = true))
        assertEquals(PermissionStatus.Denied, overlayPermissionStatus(permissionGranted = false))
    }
}
