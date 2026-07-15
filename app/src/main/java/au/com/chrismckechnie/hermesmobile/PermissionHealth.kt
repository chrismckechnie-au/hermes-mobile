package au.com.chrismckechnie.hermesmobile

enum class PermissionStatus {
    NotRequired,
    Granted,
    Denied,
}

data class PermissionHealth(
    val notifications: PermissionStatus,
    val overlay: PermissionStatus,
    val canRequestNotificationPermission: Boolean = false,
)

internal fun notificationPermissionStatus(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): PermissionStatus = when {
    !notificationsEnabled -> PermissionStatus.Denied
    sdkInt < 33 -> PermissionStatus.NotRequired
    permissionGranted -> PermissionStatus.Granted
    else -> PermissionStatus.Denied
}

internal fun overlayPermissionStatus(permissionGranted: Boolean): PermissionStatus =
    if (permissionGranted) PermissionStatus.Granted else PermissionStatus.Denied

internal fun shouldAutomaticallyRequestNotificationPermission(
    hasNotificationSubscriptions: Boolean,
    notificationStatus: PermissionStatus,
    canRequestPermission: Boolean,
    hasRequestedAutomatically: Boolean,
): Boolean = hasNotificationSubscriptions &&
    notificationStatus == PermissionStatus.Denied &&
    canRequestPermission &&
    !hasRequestedAutomatically
