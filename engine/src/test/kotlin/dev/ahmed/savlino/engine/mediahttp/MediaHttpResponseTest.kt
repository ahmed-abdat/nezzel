package dev.ahmed.savlino.engine.mediahttp

import java.io.IOException
import org.junit.Test
import kotlin.test.*

class MediaHttpResponseTest {
    @Test fun exactNearCapBodyChangesOwnerWithoutCopying() {
        for (size in listOf(2 * 1024 * 1024 - 1, 10 * 1024 * 1024 - 1)) {
            MediaHttpClient.create().use { client ->
                client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    val bytes = ByteArray(size) { 42 }
                    val response = MediaHttpResponse(null, size.toLong(),
                        MediaHttpTarget("https://media.test/synthetic", token.id, MediaHttpPurpose.IMAGE), bytes)
                    var retired = 0
                    response.onClose = { retired++ }
                    assertSame(bytes, response.takeBody().bytes)
                    assertEquals(1, retired)
                    assertFailsWith<IOException> { response.takeBody() }
                    response.close()
                    assertEquals(1, retired)
                }
            }
        }
    }

    @Test fun spareCapacityTransfersWithoutCopyWithMeasuredLength() {
        MediaHttpClient.create().use { client ->
            client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                val bytes = ByteArray(10 * 1024 * 1024) { 42 }
                val response = MediaHttpResponse(null, (bytes.size - 1).toLong(),
                    MediaHttpTarget("https://media.test/synthetic", token.id, MediaHttpPurpose.IMAGE), bytes)
                val body = response.takeBody()
                assertSame(bytes, body.bytes)
                assertEquals(bytes.size - 1, body.length)
            }
        }
    }

    @Test fun closedResponseCannotTransferAndRetiresOnlyOnce() {
        MediaHttpClient.create().use { client ->
            client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                val response = MediaHttpResponse(null, 3,
                    MediaHttpTarget("https://media.test/synthetic", token.id, MediaHttpPurpose.IMAGE), byteArrayOf(1, 2, 3))
                var retired = 0
                response.onClose = { retired++ }
                response.close()
                assertFailsWith<IOException> { response.takeBody() }
                response.close()
                assertEquals(1, retired)
            }
        }
    }
}
