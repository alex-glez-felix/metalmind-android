package io.github.alexglezfelix.metalmind.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Un `.md` del vault tal como lo lista la carpeta, sin leerlo. [path] es la
 * ruta dentro de la carpeta elegida (`proyectos/rust.md`): de ahí salen el
 * título y los `[[links]]`.
 */
data class NoteFile(val id: String, val modified: Long, val size: Long, val path: String = id)

/** Un tag para sugerir: su forma más usada y en cuántas notas está. */
data class TagSuggestion(val name: String, val notes: Int)

/**
 * Una nota para sugerir al escribir `[[`: su [title], la carpeta donde está
 * ([folder], vacía en la raíz) y [target], lo que va entre `[[` y `]]`.
 */
data class LinkSuggestion(val title: String, val folder: String, val target: String)

/** Una nota del índice, para mostrarla. [text] es el archivo entero, en NFC. */
data class IndexedNote(val id: String, val path: String, val modified: Long, val text: String) {
    /** El nombre del archivo sin `.md` ni carpetas, como Metalmind. */
    val title: String get() = NoteSearch.title(path)
}

/**
 * Lo leído de cada nota del vault: su texto, para buscar y mostrarla, y sus
 * tags, para sugerirlos al escribir `#` y filtrar la búsqueda. Es inmutable:
 * [refresh] devuelve otro índice y solo lee las notas que cambiaron (otra
 * fecha, tamaño o ruta) desde la última vez, así con miles de notas solo la
 * primera pasada las lee todas. Se guarda con [write] en la caché de la app,
 * nunca en el vault.
 */
class VaultIndex private constructor(private val files: Map<String, Entry>) {
    /** Lo de una nota: su texto y cuántas veces aparece cada forma escrita de tag (en NFC). */
    private class Entry(
        val modified: Long,
        val size: Long,
        val path: String,
        val text: String,
        val forms: Map<String, Int>,
    ) {
        /** Lo que hace falta para buscar; se calcula una vez por nota, no en cada búsqueda. */
        val searchable: NoteSearch.Searchable by lazy { NoteSearch.Searchable(path, text, forms.keys.mapTo(HashSet(), TagName::key)) }
    }

    private class Tag(val notes: Int, val name: String, val key: String)

    /** Una nota como destino de link: su ruta sin `.md` tal cual y en [linkKey], y su título plegado. */
    private class LinkName(val modified: Long, val path: String, val key: String) {
        val title = NoteSearch.title(path)
        val folded = NoteSearch.fold(title)
        val foldedPath = NoteSearch.fold(path)
        val depth = path.count { it == '/' }
    }

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

    /** Las notas como destino de link, de la más reciente a la más vieja. */
    private val linkNames: List<LinkName> by lazy {
        files.values
            .map { val path = TagName.nfc(stripMd(it.path)); LinkName(it.modified, path, linkKey(path)) }
            .sortedWith(compareByDescending<LinkName> { it.modified }.thenBy { it.path })
    }

    /** Las de [linkNames] por nombre sin mayúsculas, para ver si un destino empata. */
    private val linksByName: Map<String, List<LinkName>> by lazy {
        linkNames.groupBy { it.key.substringAfterLast('/') }
    }

    /**
     * Las notas para un `[[link]]` que sigue con [prefix] (lo escrito tras el
     * `[[`), sin distinguir mayúsculas ni acentos: primero las que empiezan
     * así, después las que lo tienen en el nombre y al final en la ruta
     * (`proy/ru` encuentra `proyectos/rust`); en cada grupo, la más reciente
     * primero. Vacío, las últimas que cambiaron. Las notas cuyo nombre no cabe
     * en un `[[link]]` (con `[`, `]`, `|` o `#`) no salen.
     */
    fun linkSuggestions(prefix: String, limit: Int = 20): List<LinkSuggestion> {
        val wanted = NoteSearch.fold(TagName.nfc(prefix.trim()))
        val writable = linkNames.asSequence().filter { note -> note.path.none { it in "[]|#" } }
        val starts = writable.filter { it.folded.startsWith(wanted) }
        val inTitle = writable.filter { !it.folded.startsWith(wanted) && wanted in it.folded }
        val inPath = writable.filter { wanted !in it.folded && wanted in it.foldedPath }
        return (starts + inTitle + inPath).take(limit).map { note ->
            LinkSuggestion(note.title, note.path.substringBeforeLast('/', ""), target(note))
        }.toList()
    }

    /**
     * Lo más corto que lleva a [note] desde una nota en la raíz de la carpeta,
     * como el autocompletado de Metalmind (`links/complete.rs`): el nombre si
     * no empata con otro, si no la ruta con las carpetas que hagan falta.
     * Empatar es que otra nota con ese final de ruta esté a la misma
     * profundidad o menos; entre las igual de hondas gana la única escrita
     * igual, como `resolve_name` de Metalmind.
     */
    private fun target(note: LinkName): String {
        val parts = note.path.split('/')
        val sameName = linksByName[note.key.substringAfterLast('/')].orEmpty()
        for (n in 1..parts.size) {
            val candidate = parts.subList(parts.size - n, parts.size).joinToString("/")
            val key = linkKey(candidate)
            val matching = sameName.filter { it.key == key || it.key.endsWith("/$key") }
            val shallowest = matching.minOf { it.depth }
            val tied = matching.filter { it.depth == shallowest }
            if (tied.size == 1 && tied[0] === note) return candidate
            val exact = tied.filter { it.path == candidate || it.path.endsWith("/$candidate") }
            if (exact.size == 1 && exact[0] === note) return candidate
        }
        return note.path
    }

    /** Las notas que coinciden con [query] (ver [NoteSearch]), las mejores primero. */
    fun search(query: String, limit: Int = 50): List<SearchHit> =
        NoteSearch.search(
            files.entries.map { (id, entry) -> NoteSearch.Candidate(id, entry.modified, entry.searchable) },
            NoteSearch.parse(query),
            limit,
        )

    fun note(id: String): IndexedNote? = files[id]?.let { IndexedNote(id, it.path, it.modified, it.text) }

    /**
     * La nota a la que lleva un `[[link]]`: sin el `#encabezado`, con o sin
     * `.md`, por nombre o por ruta (`[[rust]]` o `[[proyectos/rust]]`) y sin
     * distinguir mayúsculas. Si hay varias con ese nombre, la de ruta más
     * corta, como Obsidian; entre las igual de cortas, la escrita igual, como
     * Metalmind.
     */
    fun resolve(target: String): IndexedNote? {
        val written = TagName.nfc(target.substringBefore('#').substringBefore('^').trim().removeSuffix(".md"))
        val wanted = linkKey(written)
        if (wanted.isEmpty()) return null
        return files.entries
            .filter { (_, entry) ->
                val path = linkKey(stripMd(entry.path))
                path == wanted || path.endsWith("/$wanted")
            }
            .minWithOrNull(
                compareBy<Map.Entry<String, Entry>> { it.value.path.count { c -> c == '/' } }
                    .thenBy { TagName.nfc(stripMd(it.value.path)).let { p -> p != written && !p.endsWith("/$written") } }
                    .thenBy { it.value.path },
            )
            ?.let { (id, entry) -> IndexedNote(id, entry.path, entry.modified, entry.text) }
    }

    private fun linkKey(s: String) = TagName.nfc(s).lowercase(Locale.ROOT)

    private fun stripMd(path: String) = if (path.endsWith(".md", ignoreCase = true)) path.dropLast(3) else path

    /**
     * El índice de las notas de [listing]: las que no cambiaron salen de este,
     * las nuevas o cambiadas se leen con [read] (`null` si no se pudo: se
     * vuelve a intentar la próxima vez) y las que ya no están se olvidan.
     */
    fun refresh(listing: List<NoteFile>, read: (NoteFile) -> String?): VaultIndex {
        var changed = listing.size != files.size
        val next = HashMap<String, Entry>(listing.size)
        for (file in listing) {
            val old = files[file.id]
            if (old != null && old.modified == file.modified && old.size == file.size && old.path == file.path) {
                next[file.id] = old
                continue
            }
            changed = true
            val text = TagName.nfc(read(file) ?: continue)
            val forms = HashMap<String, Int>()
            for (tag in TagScanner.tags(text)) forms.merge(tag, 1, Int::plus)
            next[file.id] = Entry(file.modified, file.size, file.path, text, forms)
        }
        return if (changed) VaultIndex(next) else this
    }

    fun write(output: OutputStream) {
        val out = DataOutputStream(output.buffered())
        out.writeInt(FORMAT)
        out.writeInt(files.size)
        for ((id, entry) in files) {
            out.writeUTF(id)
            out.writeLong(entry.modified)
            out.writeLong(entry.size)
            out.writeUTF(entry.path)
            // writeUTF no pasa de 64 KB: el texto va como bytes con su largo.
            val text = entry.text.toByteArray(Charsets.UTF_8)
            out.writeInt(text.size)
            out.write(text)
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
        private const val FORMAT = 2

        /** Más que el límite de lo que se lee de una nota: un largo mayor es basura. */
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

        val EMPTY = VaultIndex(emptyMap())

        /** La caché de [write]; vacío si es de otro formato o está rota (truncada, con basura). */
        fun read(input: InputStream): VaultIndex = try {
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
                    val path = data.readUTF()
                    val length = data.readInt()
                    if (length !in 0..MAX_TEXT_BYTES) throw IOException("texto de $length bytes")
                    val text = ByteArray(length).also(data::readFully).toString(Charsets.UTF_8)
                    val forms = HashMap<String, Int>()
                    repeat(data.readInt()) { forms[data.readUTF()] = data.readInt() }
                    files[id] = Entry(modified, size, path, text, forms)
                }
                VaultIndex(files)
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
