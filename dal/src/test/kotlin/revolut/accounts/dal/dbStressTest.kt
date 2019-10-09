package revolut.accounts.dal

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.Valid
import revolut.accounts.dal.Deps.db
import revolut.accounts.dal.Deps.dbInitializer
import java.util.*
import kotlin.collections.ArrayList
import kotlin.random.Random
import kotlin.random.nextInt

class DbStressTest {
    private val log = LogManager.getLogger()

    /**
     * This is not a proof we don't loose money.
     * Probably TLA+ will suit for that.
     * Nevertheless here is a good way to start
     */
    @Test
    fun `stress-test that we never loose money`() {
        val alice = dbInitializer.createUser()
        val bob = dbInitializer.createUser()

        // at the start both Alice and Bob possess 1M in testerium.
        dbInitializer.createAccount(alice, 1_000_000_U)

        val aliceAccounts = (db.accounts(alice.id) as Valid).value
        val bobAccount = (db.accounts(bob.id) as Valid).value[0]

        // create batch operation plan

        val t9ns = ArrayList<T9n>(10_000)
        val rand = Random.Default

        repeat(10000) {
            val amount = rand.nextInt(1..500_000).toUInt()
            val t9nExternalId = T9nExternalId(UUID.randomUUID())
            val result = if (rand.nextBoolean())
                db.createOutgoingTransaction(t9nExternalId, alice.id, aliceAccounts[rand.nextInt(0..1)].id, bob.id, amount)
            else
                db.createOutgoingTransaction(t9nExternalId, bob.id, bobAccount.id, alice.id, amount)
            t9ns += (result as Valid).value
        }

        runBlocking {
            var i = 0
            t9ns.forEach {
                // launch all 10_000 transactions concurrently
                launch {
                    val id = it.id
                    ++i
                    log.debug("{}: debit sender for id {}", i, id)
                    db.debitSender(id)
                    log.debug("{}: credit recipient for id {}", i, id)
                    db.creditRecipient(id)
                }
            }
        }

        val aliceAccountsAfter = (db.accounts(alice.id) as Valid).value
        val bobAccountAfter = (db.accounts(bob.id) as Valid).value[0]

        val alice1 = aliceAccountsAfter[0].amount
        val alice2 = aliceAccountsAfter[1].amount
        val bob1 = bobAccountAfter.amount

        // this is a probabilistic assumption:
        // in the result of random money transfers
        // it distributes between all three accounts
        assertThat(alice1).isGreaterThan(0U)
        assertThat(alice2).isGreaterThan(0U)
        assertThat(bob1).isGreaterThan(0U)

        assertThat(alice1 + alice2 + bob1).`as`("never loose money").isEqualTo(1_000_000_U)
    }

}