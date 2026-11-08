package com.omiyage.backend.web

import arrow.core.left
import arrow.core.right
import com.omiyage.backend.errors.MediaError
import com.omiyage.backend.errors.UserError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class DomainErrorMapperTest {

    @Test
    fun `a right is unwrapped`() {
        assertEquals("ok", "ok".right().getOrRespond())
    }

    @Test
    fun `a left is raised for the exception handler with its error intact`() {
        val thrown = assertThrows(DomainErrorException::class.java) { MediaError.EmptyFile.left().getOrRespond() }

        assertEquals(MediaError.EmptyFile, thrown.error)
    }

    @Test
    fun `an upstream rejection keeps the status and message MeishiApi gave`() {
        val failure = UserError.UpstreamRejected(HttpStatus.CONFLICT, "nickname taken").toHttpFailure()

        assertEquals(HttpFailure(HttpStatus.CONFLICT, "nickname taken"), failure)
    }

    @Test
    fun `a missing image reads like the bare 404 it always was`() {
        assertEquals(HttpFailure(HttpStatus.NOT_FOUND, "404 NOT_FOUND"), MediaError.ImageNotFound.toHttpFailure())
    }
}
