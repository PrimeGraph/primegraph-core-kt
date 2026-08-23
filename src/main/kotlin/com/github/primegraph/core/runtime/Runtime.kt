package com.github.primegraph.core.runtime

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The namespace generated code spells as `Runtime.…`. Only the members that
 * cross a package boundary live here — the carriers a model field or a block
 * signature can name. Per-bundle machinery (Firebase, HTTP transport, and the
 * pure expression helpers) stays inside the generated package that needs it.
 */
public object Runtime {
    // A model field may declare `format: file`, and a model is serializable, so
    // the carrier it lands in has to be too. Its bytes travel the way every other
    // ByteArray does — base64, through the codec's contextual serializer.
    @Serializable
    public data class File(
        public val name: String,
        public val mimeType: String,
        @Contextual public val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is File) return false
            return name == other.name &&
                mimeType == other.mimeType &&
                data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    public data class FormPart(
        public val name: String,
        public val value: String = "",
        public val filename: String = "",
        public val contentType: String = "",
        public val data: ByteArray = ByteArray(0),
        public val isFile: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FormPart) return false
            return name == other.name &&
                value == other.value &&
                filename == other.filename &&
                contentType == other.contentType &&
                data.contentEquals(other.data) &&
                isFile == other.isFile
        }
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + value.hashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + contentType.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + isFile.hashCode()
            return result
        }
    }

    public fun formField(name: String, value: String): FormPart {
        return FormPart(name = name, value = value)
    }
}
