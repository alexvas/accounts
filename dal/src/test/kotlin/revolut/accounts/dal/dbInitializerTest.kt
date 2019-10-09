package revolut.accounts.dal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.dal.Deps.db
import revolut.accounts.dal.Deps.dbInitializer

class DbInitializerTest {

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

    @Test
    fun `create additional account for the user`() {
        val charlie = dbInitializer.createUser()
        val charlieAdditinalAccount = dbInitializer.createAccount(charlie, 100U)
        assertThat(charlieAdditinalAccount.settlement).isFalse()

        val accountsResult = db.accounts(charlie.id)
        assertThat(accountsResult).isInstanceOf(Valid::class.java)

        val accounts = (accountsResult as Valid).value
        assertThat(accounts)
                .isNotEmpty
                .hasSize(2)
                .contains(charlieAdditinalAccount)
    }

}