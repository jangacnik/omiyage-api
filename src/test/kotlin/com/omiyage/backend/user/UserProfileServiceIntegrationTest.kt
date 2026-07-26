package com.omiyage.backend.user

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class UserProfileServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var service: UserProfileService

    @Autowired
    lateinit var repository: UserProfileRepository

    @Test
    fun `ensureProfile creates a profile on first sight`() {
        val principal = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

        val profile = service.ensureProfile(principal)

        assertEquals("bob", profile.nickname)
        assertNull(profile.email)
        assertEquals(principal.id, repository.findById(principal.id).get().id)
    }

    @Test
    fun `ensureProfile syncs a changed nickname on later calls`() {
        val id = UUID.randomUUID()
        service.ensureProfile(MeishiPrincipal(id, "bob", Role.USER))

        val updated = service.ensureProfile(MeishiPrincipal(id, "bobby", Role.USER))

        assertEquals("bobby", updated.nickname)
        assertEquals("bobby", repository.findById(id).get().nickname)
    }

    @Test
    fun `updateProfile persists editable fields without touching nickname`() {
        val principal = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)
        service.ensureProfile(principal)

        val updated = service.updateProfile(principal, "bob@example.com", "+81-90-1234-5678", "1 Shibuya, Tokyo")

        assertEquals("bob@example.com", updated.email)
        assertEquals("+81-90-1234-5678", updated.phone)
        assertEquals("1 Shibuya, Tokyo", updated.shippingAddress)
        assertEquals("bob", updated.nickname)
    }
}
