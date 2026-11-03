package io.github.alexglezfelix.metalmind.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dónde esperan los avisos: tiene que sobrevivir al proceso. */
interface NoticeStore {
    fun load(): Set<String>

    fun save(notices: Set<String>)
}

/**
 * Lo que hay que avisar la próxima vez que se vea la app. Se guarda en [store]
 * porque quien avisa puede ser otra pantalla, u otro proceso, que el que lo
 * supo. Sin orden, y dos avisos iguales cuentan como uno.
 */
class PendingNotices(private val store: NoticeStore) {
    private val _items = MutableStateFlow(store.load())
    val items: StateFlow<Set<String>> = _items.asStateFlow()

    fun add(notice: String) = synchronized(this) {
        _items.value = _items.value + notice
        store.save(_items.value)
    }

    /** Los avisos que había: ya no se repiten. */
    fun take(): Set<String> = synchronized(this) {
        val notices = _items.value
        if (notices.isNotEmpty()) {
            _items.value = emptySet()
            store.save(emptySet())
        }
        notices
    }
}
