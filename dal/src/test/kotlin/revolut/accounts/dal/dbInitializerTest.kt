package revolut.accounts.dal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid

class DbInitializerTest {
    private val testDeps = TestDeps()
    private val db = testDeps.deps.db
    private val dbInitializer = testDeps.deps.dbInitializer

    @Test
    fun `users created OK`() {
        val alice = dbInitializer.createUser()
        val bob = dbInitializer.createUser()

        assertThat(alice).isNotNull
                .isNotEqualTo(bob)

        checkSettlementAccountCreatedOK(alice.id)
        checkSettlementAccountCreatedOK(bob.id)
    }

    private fun checkSettlementAccountCreatedOK(userId: UserId) {
        val accountsResult = db.accounts(userId)
        assertThat(accountsResult).isInstanceOf(Valid::class.java)

        val accounts = (accountsResult as Valid).value
        assertThat(accounts)
                .isNotEmpty
                .hasSize(1)

        val settlementAccount = accounts[0]
        assertThat(settlementAccount).isNotNull
        assertThat(settlementAccount.userId).isEqualTo(userId)
        assertThat(settlementAccount.settlement).isTrue()
    }


}