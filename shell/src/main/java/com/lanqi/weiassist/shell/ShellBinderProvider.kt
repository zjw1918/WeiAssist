package com.lanqi.weiassist.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Where the shell helper hands over its Binder ([ShellServer.attach] → `call("attach")`). Exported, because
 * the helper runs as another user, but it accepts only the shell user (2000) and root. Nothing else here.
 */
class ShellBinderProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = Binder.getCallingUid()
        if (caller != Process.SHELL_UID && caller != Process.ROOT_UID) {
            Log.w(TAG, "refused $method from uid $caller")
            return null
        }
        if (method != ShellServer.METHOD_ATTACH) return null
        val binder = extras?.getBinder("binder") ?: return null
        val backend = ShellBackend.get(context!!)
        backend.onAttached(binder, extras.getLong("version"))
        return Bundle().apply { putBinder("client", backend.clientToken) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object { const val TAG = "ShellBinderProvider" }
}
