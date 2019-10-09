@file:Suppress("ClassName", "TestFunctionName")

package revolut.accounts.dal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.User
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.checkIfAccountBelongsToUser
import revolut.accounts.common.createOutgoingTransaction
import revolut.accounts.dal.Deps.db
import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS
import java.util.*

class DbTest {

    @Test
    fun `check account belonging`() {
        val alice = newUser
        val aliceAdditionalAccount = alice.addAccount(1_000_U)

        val bob = newUser
        val bobSettlementAccount = bob.settlement()

        assertThat(db.checkIfAccountBelongsToUser(aliceAdditionalAccount, alice)).isTrue()
        assertThat(db.checkIfAccountBelongsToUser(aliceAdditionalAccount, bob)).isFalse()
        assertThat(db.checkIfAccountBelongsToUser(bobSettlementAccount, bob)).isTrue()
        assertThat(db.checkIfAccountBelongsToUser(bobSettlementAccount, alice)).isFalse()
    }

    @Nested
    inner class `T9n creation` {

        @Test
        fun `T9n created OK`() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val created = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)
            val now = Instant.now()
            val expected = T9n(
                    id = T9nId(UUID.randomUUID()),
                    state = T9n.State.INITIATED,
                    externalId = externalId,
                    fromUser = alice.id,
                    toUser = bob.id,
                    fromAccount = aliceAdditionalAccount.id,
                    toAccount = bob.settlement().id,
                    amount = 5U,
                    created = now,
                    modified = now
            )

            assertThat(created).isEqualToIgnoringGivenFields(expected, "id", "created", "modified")
            assertThat(created.created).isCloseTo(now, within(1, SECONDS))
            assertThat(created.modified).isCloseTo(now, within(1, SECONDS))
        }

        @Test
        fun `idempotence of T9n creation`() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val created = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)
            val created2 = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)

            assertThat(created).isEqualTo(created2)
        }

        @Test
        fun `failing creation of another t9n with the same externalId`() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)

            // amount has been changed!
            val created2 = db.createOutgoingTransaction(externalId, alice, aliceAdditionalAccount, bob, 6U)

            assertThat(created2).isInstanceOf(Invalid::class.java)
            val err = (created2 as Invalid).err
            assertThat(err.code).isEqualTo(ErrCode.ENTITY_ALREADY_EXISTS)
            assertThat(err.msg).contains(externalId.toString())
        }

    }

    @Nested
    inner class `debit sender` {

        @Test
        fun ok() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val alice2bob = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)

            val result = db.debitSender(alice2bob.id)
            assertThat(result).isInstanceOf(Valid::class.java)
            val b = (result as Valid).value
            assertThat(b).isTrue()

            // re-read account from database
            val debitedAccount = alice.findAccount(aliceAdditionalAccount.id)
            assertThat(debitedAccount.amount).isEqualTo(1_000_U - 5U)

            // re-read t9n from database
            val debitedT9n = alice.findT9n(alice2bob.id)
            assertThat(debitedT9n.state).isEqualTo(T9n.State.DEBITED)
        }

        @Test
        fun idempotence() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val alice2bob = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)

            db.debitSender(alice2bob.id)
            // oops, it did it again
            val result = db.debitSender(alice2bob.id)
            assertThat(result).isInstanceOf(Valid::class.java)
            val b = (result as Valid).value
            assertThat(b).isFalse() // <-- the only difference from `Debit sender OK`

            // re-read account from database
            val debitedAccount = alice.findAccount(aliceAdditionalAccount.id)
            assertThat(debitedAccount.amount).isEqualTo(1_000_U - 5U)

            // re-read t9n from database
            val debitedT9n = alice.findT9n(alice2bob.id)
            assertThat(debitedT9n.state).isEqualTo(T9n.State.DEBITED)
        }

        @Test
        fun `insufficient funds`() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val alice2bob = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 1_001_U)

            val result = db.debitSender(alice2bob.id)
            assertThat(result).isInstanceOf(Invalid::class.java)
            val err = (result as Invalid).err
            assertThat(err.code).isEqualTo(ErrCode.INSUFFICIENT_FUNDS)

            // re-read account from database
            val debitedAccount = alice.findAccount(aliceAdditionalAccount.id)
            assertThat(debitedAccount.amount).isEqualTo(1_000_U)

            // re-read t9n from database
            val debitedT9n = alice.findT9n(alice2bob.id)
            assertThat(debitedT9n.state).isEqualTo(T9n.State.DECLINED)
        }

    }

    @Nested
    inner class `credit recipient` {

        @Test
        fun ok() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val alice2bob = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)

            db.debitSender(alice2bob.id)
            val result = db.creditRecipient(alice2bob.id)

            assertThat(result).isInstanceOf(Valid::class.java)

            val creditedAccount = bob.settlement()
            assertThat(creditedAccount.amount).isEqualTo(5U)

            // re-read t9n from database
            val creditedT9n = alice.findT9n(alice2bob.id)
            assertThat(creditedT9n.state).isEqualTo(T9n.State.COMPLETED)
        }

        @Test
        fun idempotence() {
            val alice = newUser
            val aliceAdditionalAccount = alice.addAccount(1_000_U)

            val bob = newUser

            val externalId = T9nExternalId(UUID.randomUUID())

            val alice2bob = createT9nOk(externalId, alice, aliceAdditionalAccount, bob, 5U)

            db.debitSender(alice2bob.id)
            db.creditRecipient(alice2bob.id)
            // oops, it did it again
            val result = db.creditRecipient(alice2bob.id)

            assertThat(result).isInstanceOf(Valid::class.java)

            val creditedAccount = bob.settlement()
            assertThat(creditedAccount.amount).isEqualTo(5U)

            // re-read t9n from database
            val creditedT9n = alice.findT9n(alice2bob.id)
            assertThat(creditedT9n.state).isEqualTo(T9n.State.COMPLETED)
        }

        @Test
        fun overflow() {
            val alice = newUser
            val aliceAccount1 = alice.addAccount(Integer.MAX_VALUE.toUInt())
            val aliceAccount2 = alice.addAccount(1_U)

            val bob = newUser

            val alice2bob1 = createT9nOk(T9nExternalId(UUID.randomUUID()), alice, aliceAccount1, bob, Integer.MAX_VALUE.toUInt())
            val d1 = db.debitSender(alice2bob1.id)
            assertThat(d1).isInstanceOf(Valid::class.java)
            val c1 = db.creditRecipient(alice2bob1.id)
            assertThat(c1).isInstanceOf(Valid::class.java)

            val alice2bob2 = createT9nOk(T9nExternalId(UUID.randomUUID()), alice, aliceAccount2, bob, 1_U)
            val d2 = db.debitSender(alice2bob2.id)
            assertThat(d2).isInstanceOf(Valid::class.java)
            val c2 = db.creditRecipient(alice2bob2.id)
            assertThat(c2).isInstanceOf(Invalid::class.java)

            /**
             * When overflow happens, we kind of loose money...
             * actually not, as "lost" money are counted on t9n entity in OVERFLOW state.
             * This might proceed with auto-refund in case refunds are implemented.
             * Or left as is for now, considering overflow events too rare.
             * If these events happen too often, one might need to change amount
             * from int32 into int64 (long) both in project model and database.
             */
            assertThat(bob.settlement().amount).isEqualTo(Integer.MAX_VALUE.toUInt())
            assertThat(alice.findAccount(aliceAccount1.id).amount).isEqualTo(0U)
            assertThat(alice.findAccount(aliceAccount2.id).amount).isEqualTo(0U)

            // re-read t9n from database
            val credited1 = alice.findT9n(alice2bob1.id)
            assertThat(credited1.state).isEqualTo(T9n.State.COMPLETED)
            // re-read t9n from database
            val nonCredited2 = alice.findT9n(alice2bob2.id)
            assertThat(nonCredited2.state).isEqualTo(T9n.State.OVERFLOW)
        }

    }


}

internal fun createT9nOk(
        externalId: T9nExternalId,
        fromUser: User,
        fromAccount: Account,
        toUser: User,
        amount: UInt
): T9n {
    val result = db.createOutgoingTransaction(externalId, fromUser, fromAccount, toUser, amount)
    assertThat(result).isInstanceOf(Valid::class.java)
    return (result as Valid).value
}

internal fun settlement(userId: UserId): Account {
    val result = db.accounts(userId)
    assertThat(result).isInstanceOf(Valid::class.java)
    val accounts = (result as Valid).value
    val filtered = accounts.filter { it.settlement }
    assertThat(filtered).hasSize(1)
    return filtered[0]
}

internal fun User.settlement() = settlement(id)

internal fun User.findAccount(id: AccountId): Account {
    val result = db.accounts(this.id)
    assertThat(result).isInstanceOf(Valid::class.java)
    val accounts = (result as Valid).value
    val filtered = accounts.filter { it.id == id }
    assertThat(filtered).hasSize(1)
    return filtered[0]
}

internal fun User.findT9n(id: T9nId): T9n {
    val outResult = db.outgoingTransactions(this.id, null, Integer.MAX_VALUE.toUInt())
    assertThat(outResult).isInstanceOf(Valid::class.java)
    val outT9ns = (outResult as Valid).value
    val outFiltered = outT9ns.filter { it.id == id }
    assertThat(outFiltered).hasSizeLessThanOrEqualTo(1)
    if (outFiltered.size == 1) return outFiltered[0]

    val inResult = db.incomingTransactions(this.id, null, Integer.MAX_VALUE.toUInt())
    assertThat(inResult).isInstanceOf(Valid::class.java)
    val inT9ns = (inResult as Valid).value
    val inFiltered = inT9ns.filter { it.id == id }
    assertThat(inFiltered).hasSize(1)
    return inFiltered[0]
}
