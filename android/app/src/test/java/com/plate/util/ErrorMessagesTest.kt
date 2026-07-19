package com.plate.util

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ErrorMessagesTest {

    private fun httpError(code: Int, body: String) =
        HttpException(Response.error<Any>(code, okhttp3.ResponseBody.create(null, body)))

    @Test
    fun `surfaces the FastAPI detail string instead of the bare status`() {
        val detail = "Couldn't read a menu from that link — build the restaurant by hand."
        val e = httpError(422, """{"detail":"$detail"}""")
        assertEquals(detail, e.userMessage("fallback"))
    }

    @Test
    fun `validation detail lists fall back to the caller's message`() {
        // FastAPI request-validation errors use a detail *list*, which isn't user-friendly.
        val e = httpError(422, """{"detail":[{"loc":["body","x"],"msg":"field required"}]}""")
        assertEquals("Add components manually", e.userMessage("Add components manually"))
    }

    @Test
    fun `empty or bodyless http error uses the fallback`() {
        assertEquals("fallback", httpError(500, "").userMessage("fallback"))
    }

    @Test
    fun `IO failures read as an unreachable server`() {
        assertEquals("Can't reach the Plate server", IOException("timeout").userMessage("fallback"))
    }

    @Test
    fun `non-http errors keep their own message`() {
        assertEquals("boom", IllegalStateException("boom").userMessage("fallback"))
    }
}
