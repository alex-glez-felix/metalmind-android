package io.github.alexglezfelix.metalmind

import android.content.Context
import android.content.Intent
import android.net.Uri

/** La carpeta del vault elegida, con su permiso persistente. */
class VaultSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    /** La carpeta guardada, si todavía hay permiso para escribir en ella. */
    val folder: Uri?
        get() {
            val uri = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
            val granted = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            return uri.takeIf { granted }
        }

    fun choose(tree: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, FLAGS)
        // Soltar la anterior: la app no debe quedarse con acceso a carpetas que ya no usa.
        resolver.persistedUriPermissions
            .filter { it.uri != tree }
            .forEach {
                val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                resolver.releasePersistableUriPermission(it.uri, flags)
            }
        prefs.edit().putString(KEY_TREE, tree.toString()).apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_TREE).apply()
    }

    private companion object {
        const val KEY_TREE = "tree"
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
