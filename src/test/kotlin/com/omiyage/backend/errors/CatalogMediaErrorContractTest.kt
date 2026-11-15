package com.omiyage.backend.errors

import com.omiyage.backend.catalog.CreateCategoryRequest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryStatus
import com.omiyage.backend.catalog.ItemTemplateRequest
import com.omiyage.backend.catalog.UpdateStatusRequest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.media.Media
import com.omiyage.backend.media.MediaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.math.BigDecimal
import java.util.UUID

private val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

class CatalogMediaErrorContractTest : ErrorContractTest() {

    @Autowired
    lateinit var mediaRepository: MediaRepository

    private val user = newCaller()
    private val admin = newCaller("ADMIN")

    private fun inventoryRequest(
        isPreorder: Boolean = false,
        categoryIds: List<UUID> = emptyList(),
        tags: List<String> = emptyList(),
        imageKeys: List<String> = emptyList(),
    ) = InventoryItemRequest(
        title = uniqueTitle(),
        description = null,
        condition = Condition.NEW,
        priceAmount = BigDecimal("10.00"),
        currency = Currency.EUR,
        imageKeys = imageKeys,
        categoryIds = categoryIds,
        tags = tags,
        isPreorder = isPreorder,
    )

    private fun upload(who: Caller, bytes: ByteArray, contentType: String): MvcResult =
        mockMvc.perform(
            multipart("/api/images")
                .file(MockMultipartFile("file", "photo.jpg", contentType, bytes))
                .header("Authorization", "Bearer ${who.token}"),
        ).andReturn()

    @Test
    fun `duplicate category slug is 409`() {
        val slug = "slug-${UUID.randomUUID()}"
        assertEquals(201, send(admin, HttpMethod.POST, "/api/admin/categories", CreateCategoryRequest("Name", slug)).response.status)
        assertConflict(send(admin, HttpMethod.POST, "/api/admin/categories", CreateCategoryRequest("Other", slug)), "slug already in use")
    }

    @Test
    fun `deleting an unknown category is 404`() {
        assertNotFound(send(admin, HttpMethod.DELETE, "/api/admin/categories/${UUID.randomUUID()}"), "category not found")
    }

    @Test
    fun `updating an unknown item template is 404`() {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/item-templates/${UUID.randomUUID()}", ItemTemplateRequest(name = "template"))
        assertNotFound(result, "item template not found")
    }

    @Test
    fun `deleting an unknown item template is 404`() {
        assertNotFound(send(admin, HttpMethod.DELETE, "/api/admin/item-templates/${UUID.randomUUID()}"), "item template not found")
    }

    @Test
    fun `reading an unknown inventory item is 404`() {
        assertNotFound(send(user, HttpMethod.GET, "/api/inventory/${UUID.randomUUID()}"), "inventory item not found")
    }

    @Test
    fun `updating an unknown inventory item is 404`() {
        assertNotFound(send(admin, HttpMethod.PUT, "/api/admin/inventory/${UUID.randomUUID()}", inventoryRequest()), "inventory item not found")
    }

    @Test
    fun `changing the status of an unknown inventory item is 404`() {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/inventory/${UUID.randomUUID()}/status", UpdateStatusRequest(InventoryStatus.ARCHIVED))
        assertNotFound(result, "inventory item not found")
    }

    @Test
    fun `deleting an unknown inventory item is 404`() {
        assertNotFound(send(admin, HttpMethod.DELETE, "/api/admin/inventory/${UUID.randomUUID()}"), "inventory item not found")
    }

    @Test
    fun `creating a preorder item without a release date is 400`() {
        assertBadRequest(send(admin, HttpMethod.POST, "/api/admin/inventory", inventoryRequest(isPreorder = true)), "a preorder item must have an expected release date")
    }

    @Test
    fun `updating to a preorder item without a release date is 400`() {
        val result = send(admin, HttpMethod.PUT, "/api/admin/inventory/${UUID.randomUUID()}", inventoryRequest(isPreorder = true))
        assertBadRequest(result, "a preorder item must have an expected release date")
    }

    @Test
    fun `an unknown category id on an inventory item is 400`() {
        val result = send(admin, HttpMethod.POST, "/api/admin/inventory", inventoryRequest(categoryIds = listOf(UUID.randomUUID())))
        assertBadRequest(result, "one or more categoryIds do not exist")
    }

    @Test
    fun `an overlong tag on an inventory item is 400`() {
        val result = send(admin, HttpMethod.POST, "/api/admin/inventory", inventoryRequest(tags = listOf("x".repeat(51))))
        assertBadRequest(result, "a tag can be at most 50 characters")
    }

    @Test
    fun `an unknown image key on an inventory item is 400`() {
        val result = send(admin, HttpMethod.POST, "/api/admin/inventory", inventoryRequest(imageKeys = listOf("nope")))
        assertBadRequest(result, "unknown image key: nope")
    }

    @Test
    fun `uploading an empty file is 400`() {
        assertBadRequest(upload(user, ByteArray(0), "image/jpeg"), "file is empty")
    }

    @Test
    fun `uploading something that is not an image is 400`() {
        assertBadRequest(upload(user, "just text".toByteArray(), "image/jpeg"), "file content does not match an allowed image type")
    }

    @Test
    fun `uploading an image declared as another type is 400`() {
        assertBadRequest(upload(user, JPEG_BYTES, "image/png"), "file content does not match an allowed image type")
    }

    @Test
    fun `uploading past the daily quota is 429`() {
        repeat(50) {
            mediaRepository.save(Media(objectKey = "uploads/${UUID.randomUUID()}.jpeg", contentType = "image/jpeg", data = JPEG_BYTES, uploaderId = user.id))
        }
        assertError(upload(user, JPEG_BYTES, "image/jpeg"), 429, "too_many_requests", "daily upload limit reached")
    }

    @Test
    fun `downloading with a malformed filename is 404`() {
        assertNotFound(send(user, HttpMethod.GET, "/api/images/uploads/nope.txt"), "404 NOT_FOUND")
    }

    @Test
    fun `downloading an unknown image is 404`() {
        assertNotFound(send(user, HttpMethod.GET, "/api/images/uploads/${UUID.randomUUID()}.png"), "404 NOT_FOUND")
    }
}
