package revolut.accounts.web

import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpStatusCode
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
import org.junit.jupiter.api.Test
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.Db
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
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

    init {
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
        val response = withTestApplication({ this.module(db) }) {
            handleRequest(Get, "/${alice.id}/accounts").response
        }

        assertContentOk(response, aliceAccounts)

        verify {
            db.accounts(alice)
        }

        confirmVerified(db)
    }

    @Test
    fun bob() {
        val response = withTestApplication({ this.module(db) }) {
            handleRequest(Get, "/${bob.id}/accounts").response
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

        val response = withTestApplication({ this.module(db) }) {
            handleRequest(Get, "/${unknown.id}/accounts").response
        }

        assertThat(response.status()).isEqualTo(HttpStatusCode.NotFound)

        verify {
            db.accounts(unknown)
        }

        confirmVerified(db)
    }

    @Test
    fun gibberish() {

        val response = withTestApplication({ this.module(mockk()) }) {
            handleRequest(Get, "/AaAbBbCcC/accounts").response
        }

        assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
    }


}

private fun assertContentOk(response: TestApplicationResponse, expected: Any) {
    assertThat(response.status()).isEqualTo(HttpStatusCode.OK)

    val contentType = response.headers["Content-Type"]
    assertThat(contentType).isNotNull()
    assertThat(ContentType.Application.Json.match(contentType!!))

    assertThat(response.content).isEqualToIgnoringWhitespace(Gson().toJson(expected))
}


private fun newUserId() = UserId(UUID.randomUUID())

private fun UserId.newAccount(amount: Int, settlement: Boolean = true) = Account(
        id = AccountId(UUID.randomUUID()),
        userId = this,
        amount = amount,
        settlement = settlement
)