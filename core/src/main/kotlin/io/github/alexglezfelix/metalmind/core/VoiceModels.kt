package io.github.alexglezfelix.metalmind.core

/**
 * Un modelo de whisper.cpp que la app conoce: de dónde bajarlo y cómo
 * comprobar que llegó entero.
 */
data class VoiceModel(
    /** El nombre del archivo en Hugging Face, que también lo identifica. */
    val fileName: String,
    val bytes: Long,
    /** SHA-256 en hexadecimal, el `oid` de Git LFS en Hugging Face. */
    val sha256: String,
) {
    val url: String get() = "$BASE_URL/$fileName"

    val megabytes: Long get() = (bytes + 500_000) / 1_000_000

    companion object {
        const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    }
}

object VoiceModels {
    /** 60 MB. Rápido en cualquier teléfono; se le escapa alguna palabra. */
    val BASE = VoiceModel(
        "ggml-base-q5_1.bin",
        59_707_625,
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
    )

    /** 190 MB. Bastante mejor en español; unas tres veces más lento que base. */
    val SMALL = VoiceModel(
        "ggml-small-q5_1.bin",
        190_085_487,
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
    )

    /** 574 MB. El mejor; lento en el teléfono, para quien no tenga prisa. */
    val TURBO = VoiceModel(
        "ggml-large-v3-turbo-q5_0.bin",
        574_041_195,
        "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
    )

    /** Los que se ofrecen, del recomendado al más pesado. */
    val ALL = listOf(SMALL, BASE, TURBO)

    /** El modelo con ese SHA-256, si es uno de los conocidos. */
    fun bySha256(sha256: String): VoiceModel? = ALL.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }

    /** El modelo con ese nombre de archivo, si es uno de los conocidos. */
    fun byFileName(name: String): VoiceModel? = ALL.firstOrNull { it.fileName.equals(name, ignoreCase = true) }

    /**
     * Si los primeros bytes de un archivo son los de un modelo de whisper.cpp
     * (`ggml` como entero de 32 bits en little-endian, `lmgg`).
     */
    fun looksLikeModel(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 'l'.code.toByte() && header[1] == 'm'.code.toByte() &&
            header[2] == 'g'.code.toByte() && header[3] == 'g'.code.toByte()
}

/** Lo que pasa con un archivo elegido como modelo. */
sealed interface ModelCheck {
    /** Es uno de los conocidos y llegó entero. */
    data class Known(val model: VoiceModel) : ModelCheck

    /** Tiene forma de modelo de whisper.cpp pero no es de la lista: se usa tal cual. */
    data object Unknown : ModelCheck

    /** Se llama como uno de la lista pero no coincide: descarga cortada o dañada. */
    data class Corrupt(val expected: VoiceModel) : ModelCheck

    /** No es un modelo de whisper.cpp. */
    data object NotAModel : ModelCheck

    companion object {
        fun of(displayName: String?, header: ByteArray, sha256: String): ModelCheck {
            VoiceModels.bySha256(sha256)?.let { return Known(it) }
            // El navegador puede renombrar: `ggml-small-q5_1 (1).bin`.
            val named = displayName?.let { name ->
                VoiceModels.ALL.firstOrNull { name.startsWith(it.fileName.removeSuffix(".bin"), ignoreCase = true) }
            }
            if (named != null) return Corrupt(named)
            return if (VoiceModels.looksLikeModel(header)) Unknown else NotAModel
        }
    }
}
