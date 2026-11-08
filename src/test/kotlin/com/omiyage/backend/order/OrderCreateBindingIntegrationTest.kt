package com.omiyage.backend.order

import com.omiyage.backend.errors.orFail
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.TestJwt
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

private const val TEST_SECRET = "integration-test-secret"

/** POST /api/orders with the exact JSON the cart sends, varied one field at a time, through the real
 *  Jackson setup and validation. Pins which inputs are 201 and which are 400, and that a 400 names the field. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderCreateBindingIntegrationTest : PostgresIntegrationTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.meishi.jwt-secret") { TEST_SECRET }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var inventoryService: InventoryService

    private val itemId: String by lazy {
        inventoryService.create(
            InventoryItemRequest(
                title = "Cart item",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("12.00"),
                currency = Currency.EUR,
                stockQuantity = 5,
            ),
        ).orFail().id.toString()
    }

    private fun token(nickname: String = "bob") = TestJwt.sign(TEST_SECRET, nickname = nickname)

    private fun order(json: String, token: String = token()): ResultActions =
        mockMvc.perform(
            post("/api/orders").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(json),
        )

    private fun cart(line: String = """{"inventoryItemId":"$itemId","currency":"EUR","quantity":2}""", extra: String = "") =
        """{$extra"items":[$line]}"""

    @Test
    fun `the cart payload is accepted`() {
        order(cart()).andExpect(status().isCreated)
    }

    @Test
    fun `a line without the optional primitives gets their defaults (the cart omits isPreorder)`() {
        order(cart("""{"inventoryItemId":"$itemId","currency":"EUR"}""")).andExpect(status().isCreated).andExpect(jsonPath("$.items[0].quantity").value(1))
    }

    @Test
    fun `responses carry the isPreorder key the frontend reads`() {
        order(cart()).andExpect(status().isCreated).andExpect(jsonPath("$.items[0].isPreorder").value(false))
        mockMvc.perform(get("/api/inventory").param("title", "Cart item").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].isPreorder").value(false))
    }

    @Test
    fun `a nickname, null nickname or unknown fields are accepted`() {
        order(cart(extra = """"nickname":"Gifts",""")).andExpect(status().isCreated)
        order(cart(extra = """"nickname":null,""")).andExpect(status().isCreated)
        order(cart(extra = """"somethingNew":1,""")).andExpect(status().isCreated)
        order(cart("""{"inventoryItemId":"$itemId","currency":"EUR","quantity":1,"extra":true}""")).andExpect(status().isCreated)
    }

    @Test
    fun `a token without a nickname claim value is accepted (an SSO token carries the same claims)`() {
        order(cart(), token(nickname = "")).andExpect(status().isCreated)
    }

    @Test
    fun `quantity bounds are 1 to 1000`() {
        listOf(1, 1000).forEach { order(cart("""{"inventoryItemId":"$itemId","currency":"EUR","quantity":$it}""")).andExpect(status().isCreated) }
        listOf(0, 1001, -1).forEach {
            order(cart("""{"inventoryItemId":"$itemId","currency":"EUR","quantity":$it}"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.field").value(startsWith("items[0]")))
        }
    }

    @Test
    fun `a nickname over 100 characters names the field`() {
        order(cart(extra = """"nickname":"${"n".repeat(101)}",""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("nickname"))
    }

    @Test
    fun `an id that is not a uuid names the field`() {
        order(cart("""{"inventoryItemId":"12345","currency":"EUR","quantity":1}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("items[0].inventoryItemId"))
            .andExpect(jsonPath("$.error").value("items[0].inventoryItemId is not a valid id"))
            .andExpect(jsonPath("$.code").value("bad_request"))
    }

    @Test
    fun `a missing or lowercase currency names the field`() {
        order(cart("""{"inventoryItemId":"$itemId","quantity":1}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value(startsWith("items[0]")))
        order(cart("""{"inventoryItemId":"$itemId","currency":"eur","quantity":1}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value(startsWith("items[0]")))
    }

    @Test
    fun `a quantity that is null or text is a 400 naming the field`() {
        order(cart("""{"inventoryItemId":"$itemId","currency":"EUR","quantity":null}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value(startsWith("items[0]")))
        order(cart("""{"inventoryItemId":"$itemId","currency":"EUR","quantity":"x"}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value(startsWith("items[0]")))
    }

    @Test
    fun `a quantity given as a numeric string is read as a number`() {
        order(cart("""{"inventoryItemId":"$itemId","currency":"EUR","quantity":"2"}""")).andExpect(status().isCreated)
    }

    @Test
    fun `duplicate lines for one item are accepted`() {
        val line = """{"inventoryItemId":"$itemId","currency":"EUR","quantity":1}"""
        order(cart("$line,$line")).andExpect(status().isCreated)
    }

    @Test
    fun `an empty item list and a body that is not json have no usable field`() {
        order("""{"items":[]}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("items"))
        order("not json").andExpect(status().isBadRequest).andExpect(jsonPath("$.field").doesNotExist())
    }

    @Test
    fun `an unknown inventory item is a 404, not a 400`() {
        order(cart("""{"inventoryItemId":"${UUID.randomUUID()}","currency":"EUR","quantity":1}""")).andExpect(status().isNotFound)
    }
}
