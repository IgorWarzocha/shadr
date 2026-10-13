package dev.howaboua.phonedigest

enum class SetupStep { CONNECT, ALLOW, DONE, MAIN }
enum class NotificationAccess { REQUIRED, WAITING_FOR_LISTENER, CONNECTED }
object SetupFlow {
    fun restrictedSettingsHelp(apiLevel: Int, access: NotificationAccess) = apiLevel >= 33 && access == NotificationAccess.REQUIRED
    fun access(granted: Boolean, bound: Boolean) = when {
        !granted -> NotificationAccess.REQUIRED
        !bound -> NotificationAccess.WAITING_FOR_LISTENER
        else -> NotificationAccess.CONNECTED
    }
    fun initial(configured: Boolean, access: NotificationAccess) = when {
        !configured -> SetupStep.CONNECT
        access == NotificationAccess.REQUIRED -> SetupStep.ALLOW
        else -> SetupStep.MAIN
    }
    fun connected(access: NotificationAccess) = if (access == NotificationAccess.REQUIRED) SetupStep.ALLOW else SetupStep.DONE
}
