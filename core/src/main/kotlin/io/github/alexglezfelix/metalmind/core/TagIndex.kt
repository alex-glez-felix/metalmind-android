package io.github.alexglezfelix.metalmind.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** Un `.md` del vault tal como lo lista la carpeta, sin leerlo. */
data class NoteFile(val id: String, val modified: Long, val size: Long)

/** Un tag para sugerir: su forma más usada y en cuántas notas está. */
data class TagSuggestion(val name: String, val notes: Int)

/**
 * Los tags de cada nota del vault, para sugerirlos al escribir `#`. Es
 * inmutable: [refresh] devuelve otro índice y solo lee las notas que
 * cambiaron (otra fecha o tamaño) desde la última vez, así con miles de notas
 * solo la primera pasada las lee todas. Se guarda con [write] en la caché de
 * la app, nunca en el vault.
 */
class TagIndex private constructor(private val files: Map<String, Entry>) {
    /** Lo de una nota: cuántas veces aparece cada forma escrita (en NFC). */
    private class Entry(val modified: Long, val size: Long, val forms: Map<String, Int>)

    private class Tag(val notes: Int, val name: String, val key: String)

    val noteCount: Int get() = files.size

    /**
     * De más notas a menos; con las mismas notas, por nombre sin mayúsculas y
     * después tal cual, como el autocompletado de Metalmind (`complete.rs`). Se
     * muestra la forma con más apariciones en el vault, y si empatan la primera
     * en orden de bytes (`Rust` antes que `rust`), como Metalmind (05-tags §6).
     */
    private val tags: List<Tag> by lazy {
        val notes = HashMap<String, Int>()
        val forms = HashMap<String, HashMap<String, Int>>()
        for (entry in files.values) {
            val keys = HashSet<String>()
            for ((form, count) in entry.forms) {
                val key = TagName.key(form)
                keys += key
                forms.getOrPut(key, ::HashMap).merge(form, count, Int::plus)
            }
            for (key in keys) notes.merge(key, 1, Int::plus)
        }
        notes.map { (key, count) ->
            val name = forms.getValue(key).entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(TagName.byteOrder) { it.key })
                .first().key
            Tag(count, name, key)
        }.sortedWith(
            compareByDescending<Tag> { it.notes }
                .thenBy(TagName.byteOrder) { it.name.lowercase(Locale.ROOT) }
                .thenBy(TagName.byteOrder) { it.name },
        )
    }

    /**
     * Los tags que siguen a [prefix] (lo escrito tras el `#`), con la misma
     * identidad que los tags: `ru` sugiere `Rust`. Primero los que empiezan
     * así, después los que lo contienen; vacío, los más usados.
     */
    fun suggestions(prefix: String, limit: Int = 20): List<TagSuggestion> {
        val key = TagName.key(prefix)
        val starts = tags.asSequence().filter { it.key.startsWith(key) }
        val contains = tags.asSequence().filter { !it.key.startsWith(key) && key in it.key }
        return (starts + contains).take(limit).map { TagSuggestion(it.name, it.notes) }.toList()
    }

    /**
     * El índice de las notas de [listing]: las que no cambiaron salen de este,
     * las nuevas o cambiadas se leen con [read] (`null` si no se pudo: se
     * vuelve a intentar la próxima vez) y las que ya no están se olvidan.
     */
    fun refresh(listing: List<NoteFile>, read: (NoteFile) -> String?): TagIndex {
        var changed = listing.size != files.size
        val next = HashMap<String, Entry>(listing.size)
        for (file in listing) {
            val old = files[file.id]
            if (old != null && old.modified == file.modified && old.size == file.size) {
                next[file.id] = old
                continue
            }
            changed = true
            val text = read(file) ?: continue
            val forms = HashMap<String, Int>()
            for (tag in TagScanner.tags(text)) forms.merge(TagName.nfc(tag), 1, Int::plus)
            next[file.id] = Entry(file.modified, file.size, forms)
        }
        return if (changed) TagIndex(next) else this
    }

    fun write(output: OutputStream) {
        val out = DataOutputStream(output.buffered())
        out.writeInt(FORMAT)
        out.writeInt(files.size)
        for ((id, entry) in files) {
            out.writeUTF(id)
            out.writeLong(entry.modified)
            out.writeLong(entry.size)
            out.writeInt(entry.forms.size)
            for ((form, count) in entry.forms) {
                out.writeUTF(form)
                out.writeInt(count)
            }
        }
        out.flush()
    }

    companion object {
        /** Sube si cambia el formato o lo que cuenta como tag: la caché vieja se descarta. */
        private const val FORMAT = 1

        val EMPTY = TagIndex(emptyMap())

        /** La caché de [write]; vacío si es de otro formato o está rota (truncada, con basura). */
        fun read(input: InputStream): TagIndex = try {
            val data = DataInputStream(input.buffered())
            if (data.readInt() != FORMAT) {
                EMPTY
            } else {
                val count = data.readInt()
                val files = HashMap<String, Entry>(count)
                repeat(count) {
                    val id = data.readUTF()
                    val modified = data.readLong()
                    val size = data.readLong()
                    val forms = HashMap<String, Int>()
                    repeat(data.readInt()) { forms[data.readUTF()] = data.readInt() }
                    files[id] = Entry(modified, size, forms)
                }
                TagIndex(files)
            }
        } catch (_: IOException) {
            EMPTY
        } catch (_: RuntimeException) {
            // Un conteo negativo o enorme: IllegalArgumentException, NegativeArraySizeException…
            EMPTY
        } catch (_: OutOfMemoryError) {
            EMPTY
        }
    }
}
