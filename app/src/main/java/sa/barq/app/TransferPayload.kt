package sa.barq.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal object TransferPayload {
    private const val CHUNK_SIZE = 64 * 1024

    fun write(
        input: InputStream,
        output: DataOutputStream,
        size: Long,
        onBytes: (Long) -> Unit
    ) {
        require(size >= -1L) { "Invalid file size" }
        if (size >= 0L) {
            copyExact(input, output, size, onBytes)
            return
        }

        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.writeInt(count)
            output.write(buffer, 0, count)
            onBytes(count.toLong())
        }
        output.writeInt(0)
    }

    fun read(
        input: DataInputStream,
        output: OutputStream,
        size: Long,
        onBytes: (Long) -> Unit
    ) {
        require(size >= -1L) { "Invalid file size" }
        if (size >= 0L) {
            copyExact(input, output, size, onBytes)
            return
        }

        while (true) {
            val count = input.readInt()
            if (count == 0) return
            if (count !in 1..CHUNK_SIZE) throw EOFException("Invalid transfer chunk")
            val buffer = ByteArray(count)
            input.readFully(buffer)
            output.write(buffer)
            onBytes(count.toLong())
        }
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        size: Long,
        onBytes: (Long) -> Unit
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("File ended before its declared size")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
            onBytes(count.toLong())
        }
    }
}