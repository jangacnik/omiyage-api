package com.omiyage.backend.web

import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.util.UUID

data class SampleBody(val id: UUID)

@RequestMapping
class SampleController {
    @GetMapping("/sample/{id}")
    fun byId(@PathVariable id: UUID) = id.toString()

    @GetMapping("/header")
    fun header(@RequestHeader("Authorization") authorization: String) = authorization

    @PostMapping("/body")
    fun body(@RequestBody body: SampleBody) = body.id.toString()

    @GetMapping("/too-large")
    fun tooLarge(): String = throw MaxUploadSizeExceededException(10L * 1024 * 1024)

    @GetMapping("/integrity")
    fun integrity(): String = throw DataIntegrityViolationException("duplicate")

    @GetMapping("/boom")
    fun boom(): String = throw IllegalStateException("boom")
}

class GlobalExceptionHandlerTest {

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(SampleController())
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `malformed json is a 400`() {
        mockMvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `invalid uuid in body or path is a 400`() {
        mockMvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("""{"id":"nope"}"""))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/sample/nope")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `missing required header is a 400`() {
        mockMvc.perform(get("/header")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `wrong content type is a 415`() {
        mockMvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("x"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `oversized upload is a 413`() {
        mockMvc.perform(get("/too-large")).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `data integrity violation is a 409`() {
        mockMvc.perform(get("/integrity")).andExpect(status().isConflict).andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `unexpected exception stays a 500`() {
        mockMvc.perform(get("/boom")).andExpect(status().isInternalServerError)
    }
}
