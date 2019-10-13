@file:Suppress("ClassName")

package revolut.accounts.web

import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode
import io.ktor.locations.locations
import io.ktor.server.testing.TestApplicationEngine
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.Db
import revolut.accounts.common.T9n
import revolut.accounts.common.T9n.State.COMPLETED
import revolut.accounts.common.T9n.State.DEBITED
import revolut.accounts.common.T9n.State.DECLINED
import revolut.accounts.common.T9n.State.INITIATED
import revolut.accounts.common.T9n.State.OVERFLOW
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.T9nProcessor
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.web.UserLocation.CreateT9nsLocation
import revolut.accounts.web.UserLocation.IncomingT9nsLocation
import revolut.accounts.web.UserLocation.OutgoingT9nsLocation
import java.time.Instant.now
import java.util.*

class T9nTest {

    private val alice = newUserId()
    private val bob = newUserId()

    private val aliceAccounts = listOf(
            alice.newAccount(1_000),
            alice.newAccount(2_000, false)
    )

    private val bobAccount = bob.newAccount(3_000)

    private val outgoing = listOf(
            aliceAccounts[0].newT9n(bobAccount, INITIATED, 10),
            aliceAccounts[0].newT9n(bobAccount, INITIATED, 20),
            aliceAccounts[0].newT9n(bobAccount, DECLINED, 30),
            aliceAccounts[1].newT9n(bobAccount, DEBITED, 40),
            aliceAccounts[1].newT9n(bobAccount, COMPLETED, 50),
            aliceAccounts[1].newT9n(bobAccount, OVERFLOW, 60)
    )

    private val incoming = listOf(
            bobAccount.newT9n(aliceAccounts[0], INITIATED, 70),
            bobAccount.newT9n(aliceAccounts[0], COMPLETED, 80)
    )

    private val db: Db = mockk()
    private val t9nProcessor: T9nProcessor = mockk()

    @BeforeAll
    fun beforeAll() {
        // in does not really natter what exact data will return mock object
        // it matters we know what it returns and assert that
        every {
            db.incomingTransactions(UserId(any()), T9nId(any()), any())
        } answers {
            Valid(incoming)
        }

        every {
            db.outgoingTransactions(UserId(any()), T9nId(any()), any())
        } answers {
            Valid(outgoing)
        }

        every {
            t9nProcessor.makeT9n(T9nExternalId(any()), UserId(any()), AccountId(any()), UserId(any()), any())
        } answers {
            Valid(incoming[0])
        }
    }

    @AfterEach
    fun afterEach() {
        clearMocks(db, answers = false)
    }


    @Nested
    inner class `Incoming transactions` {

        @Test
        fun ok() {
            val response = withTestApplication({ this.module(db, mockk()) }) {
                handleRequest(Get, incomingUri(alice, null, 10)).response
            }
            assertContentOk(response, incoming)

            val response2 = withTestApplication({ this.module(db, mockk()) }) {
                handleRequest(Get, incomingUri(alice, incoming[0].id, 20)).response
            }
            assertContentOk(response2, incoming)

            verify {
                db.incomingTransactions(alice, null, 10)
                db.incomingTransactions(alice, incoming[0].id, 20)
            }

            confirmVerified(db)
        }

        @Test
        fun gibberishUser() {

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(Get, incomingUri("junk", null, 37)).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }

        @Test
        fun gibberishT9n() {

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(Get, incomingUri(alice.id.toString(), "gibberish", 37)).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }

        @Test
        fun nonPositiveLimit() {

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(Get, incomingUri(alice, null, 0)).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }

    }

    @Nested
    inner class `Outgoing transactions` {

        @Test
        fun ok() {
            val response = withTestApplication({ this.module(db, mockk()) }) {
                handleRequest(Get, outgoingUri(alice, null, 10)).response
            }
            assertContentOk(response, outgoing)

            val response2 = withTestApplication({ this.module(db, mockk()) }) {
                handleRequest(Get, outgoingUri(alice, outgoing[0].id, 20)).response
            }
            assertContentOk(response2, outgoing)

            verify {
                db.outgoingTransactions(alice, null, 10)
                db.outgoingTransactions(alice, outgoing[0].id, 20)
            }

            confirmVerified(db)
        }

        @Test
        fun gibberishUser() {

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(Get, outgoingUri("junk", null, 37)).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }

        @Test
        fun gibberishT9n() {

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(Get, outgoingUri(alice.id.toString(), "gibberish", 37)).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }

        @Test
        fun nonPositiveLimit() {

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(Get, outgoingUri(alice, null, 0)).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }

    }


    @Nested
    inner class `Creation of transactions` {

        @Test
        fun ok() {
            val sample = incoming[0]

            val response = withTestApplication({ this.module(mockk(), t9nProcessor) }) {
                handleRequest(
                        Put,
                        createUri(
                                sample.externalId.id.toString(),
                                sample.fromUser.id.toString(),
                                sample.fromAccount.id.toString(),
                                sample.toUser.id.toString(),
                                sample.amount
                        )
                ).response
            }
            assertContentOk(response, sample)

            verify {
                t9nProcessor.makeT9n(
                        sample.externalId, sample.fromUser, sample.fromAccount, sample.toUser, sample.amount
                )
            }

            confirmVerified(t9nProcessor)
        }


        @Test
        fun `bad external id`() {
            val sample = incoming[0]

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(
                        Put,
                        createUri(
                                "baaad",
                                sample.fromUser.id.toString(),
                                sample.fromAccount.id.toString(),
                                sample.toUser.id.toString(),
                                sample.amount
                        )
                ).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)

        }

        @Test
        fun `bad sender`() {
            val sample = incoming[0]

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(
                        Put,
                        createUri(
                                sample.externalId.id.toString(),
                                "baaad",
                                sample.fromAccount.id.toString(),
                                sample.toUser.id.toString(),
                                sample.amount
                        )
                ).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)

        }

        @Test
        fun `bad sender's account`() {
            val sample = incoming[0]

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(
                        Put,
                        createUri(
                                sample.externalId.id.toString(),
                                sample.fromUser.id.toString(),
                                "baaad",
                                sample.toUser.id.toString(),
                                sample.amount
                        )
                ).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)

        }

        @Test
        fun `bad recipient`() {
            val sample = incoming[0]

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(
                        Put,
                        createUri(
                                sample.externalId.id.toString(),
                                sample.fromUser.id.toString(),
                                sample.fromAccount.id.toString(),
                                "baaad",
                                sample.amount
                        )
                ).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)

        }

        @Test
        fun `zero amount`() {
            val sample = incoming[0]

            val response = withTestApplication({ this.module(mockk(), mockk()) }) {
                handleRequest(
                        Put,
                        createUri(
                                sample.externalId.id.toString(),
                                sample.fromUser.id.toString(),
                                sample.fromAccount.id.toString(),
                                sample.toUser.id.toString(),
                                0
                        )
                ).response
            }

            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
        }
    }

}

internal fun Account.newT9n(toAccount: Account, state: T9n.State, amount: Int): T9n {
    require(toAccount.settlement) { "not a settlement account $toAccount" }
    return T9n(
            id = T9nId(UUID.randomUUID()),
            state = state,
            amount = amount,
            externalId = T9nExternalId(UUID.randomUUID()),
            fromUser = this.userId,
            fromAccount = this.id,
            toUser = toAccount.userId,
            toAccount = toAccount.id,
            created = now(),
            modified = now()
    )
}

private fun TestApplicationEngine.incomingUri(userId: UserId, t9nId: T9nId?, limit: Int): String = incomingUri(userId.id.toString(), t9nId?.id?.toString(), limit)

private fun TestApplicationEngine.incomingUri(userId: String, t9nId: String?, limit: Int): String = application.locations.href(
        IncomingT9nsLocation(
                UserLocation(userId),
                t9nId ?: "",
                limit
        )
)

private fun TestApplicationEngine.outgoingUri(userId: UserId, t9nId: T9nId?, limit: Int): String = outgoingUri(userId.id.toString(), t9nId?.id?.toString(), limit)

private fun TestApplicationEngine.outgoingUri(userId: String, t9nId: String?, limit: Int): String = application.locations.href(
        OutgoingT9nsLocation(
                UserLocation(userId),
                t9nId ?: "",
                limit
        )
)

private fun TestApplicationEngine.createUri(external: String?, fromUser: String, fromAccount: String?, toUser: String?, amount: Int): String = application.locations.href(
        CreateT9nsLocation(
                UserLocation(fromUser),
                external ?: "",
                fromAccount ?: "",
                toUser ?: "",
                amount
        )
)
