package com.omiyage.backend.media

import com.omiyage.backend.security.CurrentUser
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
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

private val DOWNLOAD_FILENAME_FORMAT = Regex("^[0-9a-f-]{36}\\.(jpeg|png|webp|gif)$")

data class UploadResponse(val key: String)

@RestController
@RequestMapping("/api/images")
class MediaController(private val service: MediaService) {

    @PostMapping
    fun upload(@RequestParam("file") file: MultipartFile): ResponseEntity<UploadResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse(service.upload(file, CurrentUser.principal())))

    /** Public because img tags cannot send the JWT. Keys are random UUIDs and never reused, so responses are immutable. */
    @GetMapping("/uploads/{filename}")
    fun download(@PathVariable filename: String): ResponseEntity<ByteArray> {
        if (!DOWNLOAD_FILENAME_FORMAT.matches(filename)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val media = service.get("uploads/$filename")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(media.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'")
            .header("Content-Disposition", "inline")
            .body(media.data)
    }
}
