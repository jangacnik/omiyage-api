package com.omiyage.backend.media

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class UploadResponse(val key: String)

@RestController
@RequestMapping("/api/images")
class MediaController(private val service: MediaService) {

    @PostMapping
    fun upload(@RequestParam("file") file: MultipartFile): ResponseEntity<UploadResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse(service.upload(file)))
}
