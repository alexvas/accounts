package revolut.accounts.dal

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import revolut.accounts.common.T9n
import revolut.accounts.common.T9n.State.COMPLETED
import revolut.accounts.common.T9n.State.DECLINED
import revolut.accounts.common.T9nExternalId
import revolut.accounts.dal.Deps.db
import java.util.*
import kotlin.collections.ArrayList
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * 10k transactions are processed in 2 minutes on my box.
 * Set the number to a larger value to improve the quality of testing
 */
//private const val NO_OF_ITERATIONS = 10_000
private const val NO_OF_ITERATIONS = 100

class DbStressTest {
    private val log = LogManager.getLogger()

    /**
     * This is not a proof we don't loose money.
     * Probably TLA+ will suit for that.
     * Nevertheless here is a good way to start
     */
    @Test
    fun `stress-test that we never loose money`() {
        val alice = newUser
        val aliceAccount1 = alice.settlement()
        // at the start both Alice and Bob possess 1M in testerium.
        val aliceAccount2 = alice.addAccount(1_000_000_U)

        val bob = newUser
        val bobAccount = bob.settlement()

        // create batch operation plan

        val t9ns = ArrayList<T9n>(NO_OF_ITERATIONS)
        val rand = Random.Default

        repeat(NO_OF_ITERATIONS) {
            val amount = rand.nextInt(1..500_000).toUInt()
            val t9nExternalId = T9nExternalId(UUID.randomUUID())
            t9ns += if (rand.nextBoolean())
                createT9nOk(
                        t9nExternalId,
                        alice,
                        if (rand.nextBoolean()) aliceAccount1 else aliceAccount2,
                        bob,
                        amount
                )
            else
                createT9nOk(t9nExternalId, bob, bobAccount, alice, amount)
        }

        runBlocking {
            var i = 0
            t9ns.forEach {
                // launch all transactions concurrently
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

        val alice1 = alice.settlement().amount
        val alice2 = alice.findAccount(aliceAccount2.id).amount
        val bob1 = bob.settlement().amount

        assertThat(alice1 + alice2 + bob1).isEqualTo(1_000_000_U).`as`("Never loose money")

        // this is a probabilistic assumption:
        // in the result of random money transfers
        // it distributes between all three accounts
        assertThat(alice2).isGreaterThan(0U).`as`("The assert might fail when number of iterations is really high.")
        assertThat(alice1).isGreaterThan(0U).`as`("The assert might fail when number of iterations is low.")
        assertThat(bob1).isGreaterThan(0U).`as`("The assert might fail, yet it is very improbable.")

        val allT9ns = alice.outgoing() + alice.incoming()
        val states = allT9ns.asSequence().map { it.state }.toSet()
        assertThat(states)
                .containsAnyOf(COMPLETED, DECLINED).`as`("Finally every money transfer is processed")
                .containsExactlyInAnyOrder(COMPLETED, DECLINED).`as`("The assert might fail when number of iterations is low.")
    }

}