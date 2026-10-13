package dev.howaboua.phonedigest

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class AppProtection(private val context: Context, private val store: Store) {
    fun contains(pkg: String): Boolean {
        val admins = context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().map { it.packageName }
        val system = try {
            context.packageManager.getApplicationInfo(pkg, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: PackageManager.NameNotFoundException) { true }
        return Policy.protected(pkg, system, pkg in admins, store.extraProtected + store.extraAuthenticators + store.smsPackages)
    }
    fun allowedNoise() = store.noise.filterNot(::contains).toSet()
}
