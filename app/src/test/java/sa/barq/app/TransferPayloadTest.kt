package sa.barq.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TransferPayloadTest {
    @Test
    fun unknownSizePayloadRoundTripsWithoutConsumingFollowingFile() {
        val firstFile = ByteArray(150_000) { (it % 251).toByte() }
        val secondFile = "following file".toByteArray()
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).use { output ->
            TransferPayload.write(ByteArrayInputStream(firstFile), output, -1L) {}
            output.write(secondFile)
        }

        val received = ByteArrayOutputStream()
        val remaining = ByteArrayOutputStream()
        DataInputStream(ByteArrayInputStream(wire.toByteArray())).use { input ->
            TransferPayload.read(input, received, -1L) {}
            remaining.write(input.readBytes())
        }

        assertArrayEquals(firstFile, received.toByteArray())
        assertArrayEquals(secondFile, remaining.toByteArray())
    }
}