package revolut.accounts.dal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import revolut.accounts.common.Valid
import revolut.accounts.common.checkIfAccountBelongsToUser
import revolut.accounts.dal.Deps.db
import revolut.accounts.dal.Deps.dbInitializer

class DbTest {

    @Test
    fun `check account belonging`() {
        val alice = dbInitializer.createUser()
        val aliceAdditionalAccount = dbInitializer.createAccount(alice, 1_000_U)

        val bob = dbInitializer.createUser()
        val bobSettlementAccount = (db.accounts(bob.id) as Valid).value[0]

        assertThat(db.checkIfAccountBelongsToUser(aliceAdditionalAccount, alice)).isTrue()
        assertThat(db.checkIfAccountBelongsToUser(aliceAdditionalAccount, bob)).isFalse()
        assertThat(db.checkIfAccountBelongsToUser(bobSettlementAccount, bob)).isTrue()
        assertThat(db.checkIfAccountBelongsToUser(bobSettlementAccount, alice)).isFalse()
    }

}