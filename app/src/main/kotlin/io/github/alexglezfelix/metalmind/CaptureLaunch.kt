package io.github.alexglezfelix.metalmind

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Abrir la captura desde fuera de la app: el tile y el widget. */
object CaptureLaunch {
    fun intent(context: Context): Intent =
        // El mismo intent que el del launcher: si ya hay una captura abierta,
        // Android trae esa tarea con su borrador en vez de abrir otra vacía encima.
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
