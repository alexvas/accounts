package revolut.accounts.dal

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jooq.ConnectionProvider
import org.jooq.DSLContext
import revolut.accounts.common.DbInitializer
import revolut.accounts.common.User
import revolute.accounts.dal.jooq.Tables.USERS

class DbInitializerImpl(
        private val connectionProvider: ConnectionProvider
): DbInitializer {
    override fun createUser(): User = tx {
        log.trace("creating user")

        val user = insertInto(USERS)
                .defaultValues()
                .returning(USERS.ID)
                .fetchOne()
                .convert()

        newAccount(user, true)
        user
    }

    // --- supplementary functions and variables ---

    private val log: Logger = LogManager.getLogger()

    private fun <T> tx(block: DSLContext.() -> T) = connectionProvider.tx(block)

}
