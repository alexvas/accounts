package revolut.accounts.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.ErrCode.CALL_ERROR
import revolut.accounts.common.ErrCode.ENTITY_ALREADY_EXISTS
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import java.lang.reflect.Type
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

class ClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, Json.toString())

    private val alice = newUserId()
    private val bob = newUserId()

    private val aliceAccounts = listOf(
            alice.newAccount(1_000),
            alice.newAccount(2_000, false)
    )

    private val bobAccount = bob.newAccount(3_000)

    private val outgoing = listOf(
            aliceAccounts[0].newT9n(bobAccount, T9n.State.INITIATED, 10),
            aliceAccounts[0].newT9n(bobAccount, T9n.State.INITIATED, 20),
            aliceAccounts[0].newT9n(bobAccount, T9n.State.DECLINED, 30),
            aliceAccounts[1].newT9n(bobAccount, T9n.State.DEBITED, 40),
            aliceAccounts[1].newT9n(bobAccount, T9n.State.COMPLETED, 50),
            aliceAccounts[1].newT9n(bobAccount, T9n.State.OVERFLOW, 60)
    )

    private val incoming = listOf(
            bobAccount.newT9n(aliceAccounts[0], T9n.State.INITIATED, 70),
            bobAccount.newT9n(aliceAccounts[0], T9n.State.COMPLETED, 80)
    )

    private fun setupClient(handler: MockRequestHandler) = HttpClient(MockEngine) {
            install(JsonFeature) {
                serializer = JacksonSerializer {
                    setSerializationInclusion(JsonInclude.Include.NON_NULL)
                }
            }

            engine { addHandler(handler) }
        }

    private val dummyUrl = URLBuilder("http://somewhere.com")

    private val invalid = Invalid(ENTITY_ALREADY_EXISTS, "oops")

    private fun validResponse(payload: Any) = respond(gson.toJson(Valid(payload)), headers = jsonHeaders)
    private val invalidResponse = respond(gson.toJson(invalid), headers = jsonHeaders)
    private val failureResponse = respond("gateway timeout", HttpStatusCode.GatewayTimeout)

    @Nested
    inner class Accounts {

        @Test
        fun ok() {
            val c = ApiHttpClient(setupClient { validResponse(aliceAccounts) }, dummyUrl)
            val actualResponse = runBlocking { c.accounts(alice) }
            assertThat(actualResponse).isEqualTo(Valid(aliceAccounts))
        }

        @Test
        fun invalid() {
            val c = ApiHttpClient(setupClient { invalidResponse }, dummyUrl)
            val actualResponse = runBlocking { c.accounts(alice) }
            assertThat(actualResponse).isEqualTo(invalid)
        }

        @Test
        fun failure() {
            val c = ApiHttpClient(setupClient { failureResponse }, dummyUrl)
            val actualResponse = runBlocking { c.accounts(alice) }
            assertThat(actualResponse).isInstanceOf(Invalid::class.java)
            val err = (actualResponse as Invalid).err
            assertThat(err.code).isEqualTo(CALL_ERROR)
            assertThat(err.msg).contains(HttpStatusCode.GatewayTimeout.toString())
        }
    }

    @Nested
    inner class T9ns {
        @Test
        fun outgoingOkDefaultParams() {
            val c = ApiHttpClient(setupClient { validResponse(outgoing) }, dummyUrl)
            val actualResponse = runBlocking { c.outgoingT9ns(alice) }
            assertThat(actualResponse).isEqualTo(Valid(outgoing))
        }

        @Test
        fun outgoingLastParam() {
            val last = newT9nId()
            val c = ApiHttpClient(setupClient { validResponse(outgoing) }, dummyUrl)
            val actualResponse = runBlocking { c.outgoingT9ns(alice, last) }
            assertThat(actualResponse).isEqualTo(Valid(outgoing))
        }

        @Test
        fun outgoingLimitParam() {
            val c = ApiHttpClient(setupClient { validResponse(outgoing) }, dummyUrl)
            val actualResponse = runBlocking { c.outgoingT9ns(alice, limit = 50) }
            assertThat(actualResponse).isEqualTo(Valid(outgoing))
        }

        @Test
        fun incomingOkDefaultParams() {
            val c = ApiHttpClient(setupClient { validResponse(incoming) }, dummyUrl)
            val actualResponse = runBlocking { c.incomingT9ns(alice) }
            assertThat(actualResponse).isEqualTo(Valid(incoming))
        }

        @Test
        fun creation() {
            val c = ApiHttpClient(setupClient { validResponse(incoming[0]) }, dummyUrl)
            val actualResponse = runBlocking { c.createT9n(bob, newT9nExternalId(), bobAccount.id, alice, 333) }
            assertThat(actualResponse).isEqualTo(Valid(incoming[0]))
        }

    }

}


internal fun newUserId() = UserId(UUID.randomUUID())

internal fun UserId.newAccount(amount: Int, settlement: Boolean = true) = Account(
        id = AccountId(UUID.randomUUID()),
        userId = this,
        amount = amount,
        settlement = settlement
)

internal fun newT9nId() = T9nId(UUID.randomUUID())

internal fun newT9nExternalId() = T9nExternalId(UUID.randomUUID())

internal fun Account.newT9n(toAccount: Account, state: T9n.State, amount: Int): T9n {
    require(toAccount.settlement) { "not a settlement account $toAccount" }
    return T9n(
            id = newT9nId(),
            state = state,
            amount = amount,
            externalId = newT9nExternalId(),
            fromUser = this.userId,
            fromAccount = this.id,
            toUser = toAccount.userId,
            toAccount = toAccount.id,
            created = Instant.now(),
            modified = Instant.now()
    )
}

private class InstantSerializer : JsonSerializer<Instant> {
    override fun serialize(src: Instant?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement =
            if (src == null)
                JsonNull.INSTANCE
            else
                JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(src))
}

private val gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant::class.java, InstantSerializer())
        .create()
