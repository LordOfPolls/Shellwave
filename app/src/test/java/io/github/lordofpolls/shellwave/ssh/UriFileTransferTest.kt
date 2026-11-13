package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UriFileTransferTest {
    @Test
    fun overwriteTruncates() = assertEquals("wt", openMode(append = false))

    @Test
    fun appendDoesNotTruncate() = assertEquals("wa", openMode(append = true))

    @Test
    fun failedStat_isNotVerified() = assertNotNull(uploadVerificationError(null, 10L))

    @Test
    fun failedStat_withUnknownSize_isNotVerified() = assertNotNull(uploadVerificationError(null, -1L))

    @Test
    fun sizeMismatch_isReported() = assertNotNull(uploadVerificationError(5L, 10L))

    @Test
    fun matchingSize_passes() = assertNull(uploadVerificationError(10L, 10L))

    @Test
    fun unknownSourceSize_passes() = assertNull(uploadVerificationError(10L, -1L))
}
