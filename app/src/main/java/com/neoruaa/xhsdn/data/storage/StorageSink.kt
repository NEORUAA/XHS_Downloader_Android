package com.neoruaa.xhsdn.data.storage

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream

class StorageAccessException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Function used by a sink to stream bytes into its newly-created destination. */
fun interface StorageStreamWriter {
    fun write(output: OutputStream)
}

/** Platform-independent write/read boundary used by download pipelines. */
interface StorageSink {
    fun store(
        destination: StorageDestination,
        displayName: String,
        mimeType: String,
        sizeBytes: Long = 0L,
        writer: StorageStreamWriter,
    ): StoredMediaRef
}

interface StoredMediaReader {
    fun open(ref: StoredMediaRef): InputStream?
}
