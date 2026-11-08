package com.omiyage.backend.media

import arrow.core.raise.either
import arrow.core.raise.ensure
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.MediaError
import com.omiyage.backend.security.CurrentUser
import com.omiyage.backend.web.getOrRespond
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration

private val DOWNLOAD_FILENAME_FORMAT = Regex("^[0-9a-f-]{36}\\.(jpeg|png|webp|gif)$")

data class UploadResponse(val key: String)

@RestController
@RequestMapping("/api/images")
class MediaController(private val service: MediaService) {

    @PostMapping
    fun upload(@RequestParam("file") file: MultipartFile): ResponseEntity<UploadResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse(service.upload(file, CurrentUser.principal()).getOrRespond()))

    /** Public because img tags cannot send the JWT. Keys are random UUIDs and never reused, so responses are immutable. */
    @GetMapping("/uploads/{filename}")
    fun download(@PathVariable filename: String): ResponseEntity<ByteArray> {
        val media = either<DomainError, Media> {
            ensure(DOWNLOAD_FILENAME_FORMAT.matches(filename)) { MediaError.ImageNotFound }
            service.get("uploads/$filename").bind()
        }.getOrRespond()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(media.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'")
            .header("Content-Disposition", "inline")
            .body(media.data)
    }
}
