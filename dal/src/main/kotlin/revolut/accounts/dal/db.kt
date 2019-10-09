package revolut.accounts.dal

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jooq.ConnectionProvider
import org.jooq.DSLContext
import org.jooq.ResultQuery
import org.jooq.SelectWhereStep
import org.jooq.exception.DataAccessException
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.Db
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.MAX_AMOUNT
import revolut.accounts.common.OK
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import revolut.accounts.common.ok
import revolute.accounts.dal.jooq.Tables.T9NS
import revolute.accounts.dal.jooq.enums.T9nState.COMPLETED
import revolute.accounts.dal.jooq.enums.T9nState.DEBITED
import revolute.accounts.dal.jooq.enums.T9nState.DECLINED
import revolute.accounts.dal.jooq.enums.T9nState.INITIATED
import revolute.accounts.dal.jooq.enums.T9nState.OVERFLOW
import revolute.accounts.dal.jooq.tables.Accounts.ACCOUNTS
import revolute.accounts.dal.jooq.tables.records.AccountsRecord
import revolute.accounts.dal.jooq.tables.records.T9nsRecord

typealias SelectAccountsQuery = (SelectWhereStep<AccountsRecord>) -> ResultQuery<AccountsRecord>
typealias SelectT9nsQuery = (SelectWhereStep<T9nsRecord>) -> ResultQuery<T9nsRecord>

// https://www.postgresql.org/docs/current/errcodes-appendix.html
private const val UNIQUE_VIOLATION = "23505"
private const val CHECK_VIOLATION = "23514"

class DbImpl(
        private val connectionProvider: ConnectionProvider
) : Db {
    override fun accounts(userId: UserId): Validated<Err, List<Account>> = tx {
        log.trace("user's {} accounts", userId)

        if (!userExists(userId))
            return@tx invalid(ErrCode.USER_NOT_FOUND, "User $userId not found")

        Valid(
                selectAccountList {
                    it.where(ACCOUNTS.USER_ID.eq(userId.id))
                }
        )
    }

    override fun incomingTransactions(userId: UserId, lastT9nId: T9nId?, limit: UInt): Validated<Err, List<T9n>> = tx {
        log.trace("incoming transactions for userId = {}, with lastT9nId = {}, limit = {}", userId, lastT9nId, limit)

        if (!userExists(userId))
            return@tx invalid(ErrCode.USER_NOT_FOUND, "User $userId not found")

        val lastT9n = when (val result = findT9n(lastT9nId)) {
            is Invalid -> return@tx result
            is Valid -> result.value
        }

        require(limit <= MAX_AMOUNT) { "limit $limit too large" }

        Valid(
                selectT9nList {
                    it
                            .where(
                                    T9NS.TO_USER.eq(userId.id)
                                            .and(T9NS.FROM_USER.ne(userId.id))
                            )
                            .orderBy(T9NS.FROM_USER, T9NS.CREATED, T9NS.EXTERNAL_ID)
                            .also { step ->
                                lastT9n?.let { last -> step.seek(last.fromUser.id, last.created.convert(), last.externalId.id) }
                            }
                            .limit(limit.toInt())
                }
        )
    }

    override fun outgoingTransactions(userId: UserId, lastT9nId: T9nId?, limit: UInt): Validated<Err, List<T9n>> = tx {
        log.trace("outgoing transactions for userId = {}, with lastT9nId = {}, limit = {}", userId, lastT9nId, limit)

        if (!userExists(userId))
            return@tx invalid(ErrCode.USER_NOT_FOUND, "User $userId not found")

        val lastT9n = when (val result = findT9n(lastT9nId)) {
            is Invalid -> return@tx result
            is Valid -> result.value
        }

        require(limit <= MAX_AMOUNT) { "limit $limit too large" }

        Valid(
                selectT9nList {
                    it
                            .where(
                                    T9NS.FROM_USER.eq(userId.id)
                                            .and(T9NS.TO_USER.ne(userId.id))
                            )
                            .orderBy(T9NS.TO_USER, T9NS.CREATED, T9NS.EXTERNAL_ID)
                            .also { step ->
                                lastT9n?.let { last ->
                                    step.seek(last.toUser.id, last.created.convert(), last.externalId.id)
                                }
                            }
                            .limit(limit.toInt())
                }
        )
    }

    override fun checkIfAccountBelongsToUser(accountId: AccountId, userId: UserId): Boolean = tx {
        fetchExists(
                select()
                        .from(ACCOUNTS)
                        .where(
                                ACCOUNTS.ID.eq(accountId.id)
                                        .and(ACCOUNTS.USER_ID.eq(userId.id))
                        )
        )
    }

    override fun createOutgoingTransaction(
            externalId: T9nExternalId,
            fromUserId: UserId,
            fromAccountId: AccountId,
            toUserId: UserId,
            amount: UInt
    ): Validated<Err, T9n> = tx {

        // Read from DB first. Reads are cheap.
        checkIfT9nsAlreadyExists(externalId, fromUserId, fromAccountId, toUserId, amount)?.let { return@tx it }

        val created: T9nsRecord
        try {
            // ... and then try to write.
            created = insertT9n(externalId, fromUserId, fromAccountId, toUserId, amount)
        } catch (e: DataAccessException) {
            if (e.sqlState() != UNIQUE_VIOLATION) {
                throw e
            }
            // ... and still by ready to catch exception on violation of guarding unique key
            // due to the concurrency issues
            checkIfT9nsAlreadyExists(externalId, fromUserId, fromAccountId, toUserId, amount)?.let { return@tx it }
            throw e
        }

        Valid(created.convert())
    }

    /**
     * Here is one of two operation (debitSender and creditRecipient) to process a money transfer.
     *
     * N.B. #1
     * There is only one update per table here.
     * This way we prevent possible deadlocks when concurrent mutual transfers are running at the same moment,
     * e.g. from Alice to Bob _and_ from Bob to Alice.
     */
    override fun debitSender(t9nId: T9nId): Validated<Err, Boolean> = tx {
        log.trace("debit sender t9nId={}", t9nId)

        // Here is independent query to fetch data from t9n. It is possible to convert it into a subquery of next update
        // yet this way is more easy to handle the case it fails.
        //
        // N.B. #2
        // while selecting values in transaction, we also lock (pessimistically) the row in "FOR NO KEY UPDATE" mode.
        // This is exactly what update of t9n (see below, second one) will do. See Row-Level Locks section:
        // https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS
        // The lock will guard t9n state we are going to change in the very-very improbable case
        // two debitSenders will run with the same t9nId.
        val result = select(T9NS.FROM_ACCOUNT, T9NS.AMOUNT)
                .from(T9NS)
                .where(
                        T9NS.ID.eq(t9nId.id)
                                .and(T9NS.STATE.eq(INITIATED))
                ).option("FOR NO KEY UPDATE")
                .fetchOne()
                ?: return@tx Valid(false) // t9n already left INITIATED state

        val (fromAccountId, amount) = result

        // N.B. #3
        // To prevent mutual locking in concurrent execution the order locks are acquired is very important.
        // Implementing any other operation that locks both t9n and accounts tables let's first acquire a lock for
        // the former and then a lock for the latter.

        val updatedAccountsCount =
                update(ACCOUNTS)
                        .set(ACCOUNTS.AMOUNT, ACCOUNTS.AMOUNT - amount)
                        .where(
                                ACCOUNTS.ID.eq(fromAccountId)
                                        .and(ACCOUNTS.AMOUNT.ge(amount)) // Even if it is possible to rely on constraint, let's add explicit condition
                        )
                        .execute()

        if (updatedAccountsCount < 0 || updatedAccountsCount > 1)
            return@tx internal("invalid number of accounts updated: $updatedAccountsCount for t9nId $t9nId")

        if (updatedAccountsCount == 0) {
            if (!updateT9nState(t9nId, INITIATED, DECLINED))
                log.error("wrong number of t9ns updated to declined for {}", t9nId)

            return@tx invalid(ErrCode.INSUFFICIENT_FUNDS, "not enough money to make transaction $t9nId of amount $amount from account $fromAccountId")
        }

        if (!updateT9nState(t9nId, INITIATED, DEBITED))
            return@tx internal("wrong number of t9ns updated to declined for $t9nId")

        Valid(true)
    }

    /**
     * The second half of a money transfer processing also uses only one update per table.
     */
    override fun creditRecipient(t9nId: T9nId): Validated<Err, OK> = tx {
        log.trace("credit recipient t9nId={}", t9nId)

        val result = select(T9NS.TO_ACCOUNT, T9NS.AMOUNT)
                .from(T9NS)
                .where(
                        T9NS.ID.eq(t9nId.id)
                                .and(T9NS.STATE.eq(DEBITED))
                ).option("FOR NO KEY UPDATE") // again, acquire t9ns' lock first (see debitSender)
                .fetchOne()
                ?: return@tx ok // t9n already left DEBITED state or perhaps never entered into it

        val (toAccountId, amount) = result

        // ... and then accounts' lock
        val updatedAccountsCount =
                update(ACCOUNTS)
                        .set(ACCOUNTS.AMOUNT, ACCOUNTS.AMOUNT + amount)
                        .where(
                                ACCOUNTS.ID.eq(toAccountId)
                                        .and(ACCOUNTS.AMOUNT.le(Integer.MAX_VALUE - amount))
                        )
                        .execute()

        if (updatedAccountsCount < 0 || updatedAccountsCount > 1)
            return@tx internal("invalid number of accounts updated: $updatedAccountsCount for t9nId $t9nId")

        if (updatedAccountsCount == 0) {
            if (!updateT9nState(t9nId, DEBITED, OVERFLOW))
                log.error("wrong number of t9ns updated to overflow for {}", t9nId)

            return@tx invalid(ErrCode.FUNDS_OVERFLOW, "to much money on recipient's account to make transaction $t9nId of amount $amount to account $toAccountId")
        }

        if (!updateT9nState(t9nId, DEBITED, COMPLETED))
            return@tx internal("wrong number of t9ns updated to overflow for $t9nId")

        ok
    }

    // --- supplementary functions and variables ---

    private val log: Logger = LogManager.getLogger()

    private fun <T> tx(block: DSLContext.() -> T) = connectionProvider.tx(block)

    private fun selectAccountList(block: SelectAccountsQuery) =
            tx {
                block(selectFrom(ACCOUNTS)).fetch()
            }.map { it.convert() }

    private fun selectT9n(block: SelectT9nsQuery) =
            tx {
                block(selectFrom(T9NS)).fetchOne()
            }?.convert()

    private fun selectT9nList(block: SelectT9nsQuery) =
            tx {
                block(selectFrom(T9NS)).fetch()
            }.map { it.convert() }

    /**
     * Creation operations in the project are idempotent. It means it is perfectly valid
     * to find entity to be created already exists in database. It would be an error, however
     * if the entity already stored in database with the same external ID would have different
     * parameters than one going to be stored.
     */
    private fun checkIfT9nsAlreadyExists(externalId: T9nExternalId, fromUserId: UserId, fromAccountId: AccountId, toUserId: UserId, amount: UInt) =
            selectT9n {
                it.where(T9NS.EXTERNAL_ID.eq(externalId.id))
            }
                    ?.let {
                        if (
                                it.externalId == externalId &&
                                it.fromUser == fromUserId &&
                                it.fromAccount == fromAccountId &&
                                it.toUser == toUserId &&
                                it.amount == amount
                        )
                            Valid(it)
                        else
                            invalid(ErrCode.ENTITY_ALREADY_EXISTS, "different entity $it exists for input $externalId, $fromUserId, $fromAccountId, $toUserId, $amount")
                    }

}

internal fun invalid(code: ErrCode, msg: String) = Invalid(Err(code, msg))

internal fun internal(msg: String) = invalid(ErrCode.INTERNAL, msg)
