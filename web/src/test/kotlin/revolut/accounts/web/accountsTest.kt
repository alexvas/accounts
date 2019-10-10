package revolut.accounts.web

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpStatusCode
import io.ktor.locations.locations
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.TestApplicationResponse
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.Db
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.web.UserLocation.AccountsLocation
import java.lang.reflect.Type
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

class AccountsTest {

    private val alice = newUserId()
    private val bob = newUserId()

    private val aliceAccounts = listOf(
            alice.newAccount(1_000),
            alice.newAccount(2_000, false)
    )

    private val bobAccounts = listOf(
            bob.newAccount(3_000)
    )

    private val db: Db = mockk()

    @BeforeAll
    fun beforeAll() {
        every {
            db.accounts(UserId(any()))
        } answers {
            when (val uuid: UUID = firstArg()) {
                alice.id -> Valid(aliceAccounts)
                bob.id -> Valid(bobAccounts)
                else -> Invalid(Err(ErrCode.USER_NOT_FOUND, "User $uuid not found"))
            }
        }
    }

    @AfterEach
    fun afterEach() {
        clearMocks(db, answers = false)
    }

    @Test
    fun alice() {
        val response = withTestApplication({ this.module(db, mockk()) }) {
            handleRequest(Get, uri(alice)).response
        }

        assertContentOk(response, aliceAccounts)

        verify {
            db.accounts(alice)
        }

        confirmVerified(db)
    }

    @Test
    fun bob() {
        val response = withTestApplication({ this.module(db, mockk()) }) {
            handleRequest(Get, uri(bob)).response
        }

        assertContentOk(response, bobAccounts)

        verify {
            db.accounts(bob)
        }

        confirmVerified(db)
    }

    @Test
    fun unknown() {

        val unknown = newUserId()

        val response = withTestApplication({ this.module(db, mockk()) }) {
            handleRequest(Get, uri(unknown)).response
        }

        assertThat(response.status()).isEqualTo(HttpStatusCode.NotFound)
        assertThat(response.content).contains("\"msg\" : \"User ").contains(" not found\"")

        verify {
            db.accounts(unknown)
        }

        confirmVerified(db)
    }

    @Test
    fun gibberish() {

        val response = withTestApplication({ this.module(mockk(), mockk()) }) {
            handleRequest(Get, uri("AaAbBbCcC")).response
        }

        assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(response.content).contains("bad user ID")
    }


}

private class InstantSerializer : JsonSerializer<Instant> {
    override fun serialize(src: Instant?, typeOfSrc: Type?, context: JsonSerializationContext?) =
            if (src == null)
                JsonNull.INSTANCE
            else
                JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(src))
}

private val gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant::class.java, InstantSerializer())
        .create()

internal fun assertContentOk(response: TestApplicationResponse, expected: Any) {
    assertThat(response.status()).isEqualTo(HttpStatusCode.OK)

    val contentType = response.headers["Content-Type"]
    assertThat(contentType).isNotNull()
    assertThat(ContentType.Application.Json.match(contentType!!))

    assertThat(response.content).isEqualToIgnoringWhitespace(gson.toJson(expected))
}


internal fun newUserId() = UserId(UUID.randomUUID())

internal fun UserId.newAccount(amount: Int, settlement: Boolean = true) = Account(
        id = AccountId(UUID.randomUUID()),
        userId = this,
        amount = amount,
        settlement = settlement
)

private fun TestApplicationEngine.uri(userId: UserId): String = uri(userId.id.toString())

private fun TestApplicationEngine.uri(userId: String): String = application.locations.href(AccountsLocation(UserLocation(userId)))