package com.lanqi.weiassist.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * The phone's shared storage (/sdcard) for the file tools: Android 11+ asks for "All files access" on a system screen;
 * Android 8–10 for the storage permissions in a dialog.
 */
object StorageAccess {
    /** The permissions asked in a dialog, before Android 11. */
    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    val usesSystemScreen get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun granted(ctx: Context): Boolean =
        if (usesSystemScreen) Environment.isExternalStorageManager()
        else legacyPermissions.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Android 11+: this app's "All files access" screen (or the list of apps, where that screen is missing). */
    fun settingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", ctx.packageName, null))
            .takeIf { it.resolveActivity(ctx.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}

internal fun storageGranted(ctx: Context) = StorageAccess.granted(ctx)
