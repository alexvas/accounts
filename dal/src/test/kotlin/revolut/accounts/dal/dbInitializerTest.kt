package revolut.accounts.dal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import revolut.accounts.common.User
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.accounts
import revolut.accounts.dal.Deps.db
import revolut.accounts.dal.Deps.dbInitializer

class DbInitializerTest {

    @Test
    fun `users created OK`() {
        val alice = newUser
        val bob = newUser

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
        val charlie = newUser
        val charlieAdditinalAccount = charlie.addAccount(100U)
        assertThat(charlieAdditinalAccount.settlement).isFalse()

        val accountsResult = db.accounts(charlie)
        assertThat(accountsResult).isInstanceOf(Valid::class.java)

        val accounts = (accountsResult as Valid).value
        assertThat(accounts)
                .isNotEmpty
                .hasSize(2)
                .contains(charlieAdditinalAccount)
    }

}

internal val newUser
    get() = dbInitializer.createUser()

internal fun User.addAccount(amount: UInt) = dbInitializer.createAccount(this, amount)