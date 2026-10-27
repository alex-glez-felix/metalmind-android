package io.github.alexglezfelix.metalmind

import android.content.Context
import android.net.Uri
import io.github.alexglezfelix.metalmind.core.VaultIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * El índice del vault en memoria, compartido por la captura y la búsqueda
 * mientras la app vive: pasar de una a otra no vuelve a leer la caché, y
 * buscar nunca lee el vault, solo el índice.
 */
object Vaults {
    private class Loaded(val tree: Uri, val index: VaultIndex)

    @Volatile
    private var loaded: Loaded? = null
    private val refreshing = Mutex()

    /** Lo que ya hay, enseguida: en memoria o, si no, de la caché de la app. */
    suspend fun cached(context: Context, tree: Uri): VaultIndex = withContext(Dispatchers.IO) {
        loaded?.takeIf { it.tree == tree }?.index
            ?: VaultCache(context.applicationContext, tree).cached().also { keep(tree, it) }
    }

    /**
     * Al día con el vault: solo relee las notas que cambiaron. Una a la vez;
     * si la captura y la búsqueda lo piden juntas, la segunda parte del
     * resultado de la primera y ya casi no tiene nada que leer.
     */
    suspend fun refresh(context: Context, tree: Uri): VaultIndex = withContext(Dispatchers.IO) {
        refreshing.withLock {
            val cache = VaultCache(context.applicationContext, tree)
            val base = loaded?.takeIf { it.tree == tree }?.index ?: cache.cached()
            cache.refresh(base).also { keep(tree, it) }
        }
    }

    private fun keep(tree: Uri, index: VaultIndex) {
        loaded = Loaded(tree, index)
    }
}
