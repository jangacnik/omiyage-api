package com.omiyage.backend.user

import com.omiyage.backend.security.MeishiPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class UserProfileService(private val repository: UserProfileRepository) {

    /** Finds this user's profile, creating it on first sight and keeping the cached nickname in sync. */
    @Transactional
    fun ensureProfile(principal: MeishiPrincipal): UserProfile {
        val existing = repository.findById(principal.id).orElse(null)
        if (existing == null) {
            return repository.save(UserProfile(id = principal.id, nickname = principal.nickname))
        }
        if (existing.nickname != principal.nickname) {
            existing.nickname = principal.nickname
            existing.updatedAt = OffsetDateTime.now()
        }
        return existing
    }

    @Transactional
    fun updateProfile(principal: MeishiPrincipal, email: String?, phone: String?, shippingAddress: String?): UserProfile {
        val profile = ensureProfile(principal)
        profile.email = email
        profile.phone = phone
        profile.shippingAddress = shippingAddress
        profile.updatedAt = OffsetDateTime.now()
        return profile
    }
}
