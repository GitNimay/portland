package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Official AWS S3 "GET Object" SigV4 example (sig-v4-header-based-auth). */
class SigV4Test {
    @Test
    fun `matches the aws s3 get object example`() {
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val auth = sign(
            method = "GET",
            path = "/test.txt",
            query = emptyMap(),
            headers = mapOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to emptyHash,
                "x-amz-date" to "20130524T000000Z",
            ),
            payloadHash = emptyHash,
            amzDate = "20130524T000000Z",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "s3",
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            auth,
        )
    }
}
