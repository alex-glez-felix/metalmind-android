package io.github.alexglezfelix.metalmind

import android.content.Context
import io.github.alexglezfelix.metalmind.core.NoticeStore
import io.github.alexglezfelix.metalmind.core.PendingNotices

/** Los avisos de lo guardado al salir grabando; uno por proceso, para que los vea cualquier captura. */
object VoiceLeaveNotices {
    private const val KEY = "avisos_al_salir"
    private var instance: PendingNotices? = null

    @Synchronized
    fun get(app: Context): PendingNotices = instance ?: run {
        val prefs = app.getSharedPreferences("voz", Context.MODE_PRIVATE)
        PendingNotices(
            object : NoticeStore {
                override fun load(): Set<String> = prefs.getStringSet(KEY, null).orEmpty().toSet()

                override fun save(notices: Set<String>) {
                    prefs.edit().putStringSet(KEY, notices).apply()
                }
            },
        ).also { instance = it }
    }
}
