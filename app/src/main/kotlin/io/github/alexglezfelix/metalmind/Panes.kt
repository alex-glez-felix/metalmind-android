package io.github.alexglezfelix.metalmind

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Uno o dos paneles según el tamaño de la ventana, y cómo se mueve el
 * teclado por la lista de resultados.
 */
object Panes {
    /**
     * Dos paneles (lista a la izquierda, nota o captura a la derecha) si ni el
     * ancho ni el alto de la ventana son "compactos" en las clases de tamaño de
     * Material (600 dp y 480 dp). Una tablet tiene dos en las dos orientaciones;
     * un teléfono, también acostado, sigue con uno; una ventana dividida, lo que
     * le quepa.
     */
    fun two(widthDp: Int, heightDp: Int): Boolean = widthDp >= 600 && heightDp >= 480

    /** El ancho de la lista: dos quintos de la ventana, sin que quede ni angosta ni enorme. */
    fun listWidthDp(widthDp: Int): Int = (widthDp * 2 / 5).coerceIn(280, 420)

    /**
     * El resultado al que lleva una flecha (`delta` 1 abajo, -1 arriba) desde
     * [current]. Sin ninguno elegido, o con uno que ya no está en la lista,
     * empieza por el primero. En los extremos se queda donde está.
     */
    fun step(ids: List<String>, current: String?, delta: Int): String? {
        if (ids.isEmpty()) return null
        val at = ids.indexOf(current)
        if (at < 0) return ids.first()
        return ids[(at + delta).coerceIn(0, ids.lastIndex)]
    }
}

/** Cómo se reparte la ventana: si caben dos paneles y el ancho de la lista. */
class PaneLayout(val twoPanes: Boolean, val listWidth: Dp)

/**
 * Del tamaño de la ventana de la app (con la ventana dividida, lo que le
 * toca), no del espacio que deja el teclado: abrirlo no cambia la
 * disposición aunque el sistema achique la ventana.
 */
@Composable
fun rememberPaneLayout(): PaneLayout {
    val config = LocalConfiguration.current
    val width = config.screenWidthDp
    val height = config.screenHeightDp
    return PaneLayout(Panes.two(width, height), Panes.listWidthDp(width).dp)
}
