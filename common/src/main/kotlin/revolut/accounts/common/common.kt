package revolut.accounts.common

import kotlinx.coroutines.CoroutineScope
import java.time.Duration

/**
 * The way to live in a world without checked exceptions
 */
sealed class Validated<out E, out R>

data class Invalid<E>(val err: E) : Validated<E, Nothing>()
data class Valid<R>(val value: R) : Validated<Nothing, R>()

object OK

val ok = Valid(OK)

enum class ErrCode {
    INTERNAL,              // i.e. internal server error
    USER_NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    T9N_NOT_FOUND,
    OTHERS_ACCOUNT,        // the user does not own the account
    INSUFFICIENT_FUNDS,    // not enough money to perform requested transaction
    FUNDS_OVERFLOW,        // too much money on receiver's account
    ENTITY_ALREADY_EXISTS, // different entity with the same external ID already exists
}

data class Err(
        val code: ErrCode,
        val msg: String = ""
)

interface Db {

    /**
     * What my accounts are?
     * returns all user's accounts
     */
    fun accounts(userId: UserId): Validated<Err, List<Account>>

    /**
     * What brings me money?
     * get a page of user's transactions using SEEK method
     * see https://blog.jooq.org/2013/11/18/faster-sql-pagination-with-keysets-continued/
     *
     * @param userId -- recipient of transactions
     * @param lastT9nId -- previous page boundary. If set, return list following transactions
     * @param limit -- haw many result needed
     */
    fun incomingTransactions(userId: UserId, lastT9nId: T9nId?, limit: Int): Validated<Err, List<T9n>>

    /**
     * What takes my money away?
     * get a page of user's transactions using SEEK method
     *
     * @param userId -- initiator of transactions
     * @param lastT9nId -- previous page boundary. If set, return list following transactions
     * @param limit -- haw many result needed
     */
    fun outgoingTransactions(userId: UserId, lastT9nId: T9nId?, limit: Int): Validated<Err, List<T9n>>

    /* to be implemented in the next phase:
     * How I manage my money?
     * get a page of user's transactions using SEEK method
     *
     * @param userId -- owner of transactions
     * @param lastT9nId -- previous page boundary. If set, return list following transactions
     * @param limit -- haw many result needed
       fun selfTransactions(userId: UserId, lastT9nId: T9nId?, limit: Int)
     */

    fun checkIfAccountBelongsToUser(accountId: AccountId, userId: UserId): Boolean

    /**
     * send money to somebody
     *
     * @param externalId is used to make operation idempotent
     * @param fromUserId who is sending money
     * @param fromAccountId what account should be debited. The account must belong to the sender.
     * @param toUserId who is transaction recipient
     * @param amount of money to send
     *
     * @return either created transaction in INITIATED state or error
     */
    fun createOutgoingTransaction(
            externalId: T9nExternalId,
            fromUserId: UserId,
            fromAccountId: AccountId,
            toUserId: UserId,
            amount: Int
    ): Validated<Err, T9n>

    /**
     * atomically both debit sender and
     * change transaction state INITIATED => DEBITED
     * if sender got enough money to perform a transaction
     *
     * in the case of insufficient funds
     * change transaction state INITIATED => DECLINED
     * and return correspondent error
     *
     * in the case t9n already left INITIATED state, no debit is needed,
     * the operation will return false
     */
    fun debitSender(t9nId: T9nId): Validated<Err, Boolean>

    /**
     * atomically both credit recipient and
     * change transaction state DEBITED => COMPLETED
     */
    fun creditRecipient(t9nId: T9nId): Validated<Err, OK>

    /**
     * find stale transaction is INITIATED state
     */
    fun staleInitiated(durationToBecomeStale: Duration, maxBatchSize: Int): List<T9n>

    /**
     * find stale transaction is DEBITED state
     */
    fun staleDebited(durationToBecomeStale: Duration, maxBatchSize: Int): List<T9n>

}

fun Db.accounts(user: User) = accounts(user.id)

fun Db.createOutgoingTransaction(
        externalId: T9nExternalId,
        fromUser: User,
        fromAccount: Account,
        toUser: User,
        amount: Int
) = createOutgoingTransaction(externalId, fromUser.id, fromAccount.id, toUser.id, amount)

fun Db.checkIfAccountBelongsToUser(account: Account, user: User) = checkIfAccountBelongsToUser(account.id, user.id)

/**
 * As the test task is "Keep it simple and up to the point"
 * here are supplementary functions to fill database.
 *
 * These functions are not intended to use in API calls,
 * so they are simplified a bit. E.g. entity creation operations
 * are not idempotent.
 */
interface DbInitializer {

    /**
     * create a user with their settlement account
     */
    fun createUser(): User

    /**
     * create a (non-settlement) account for given user
     */
    fun createAccount(user: User, amount: Int): Account
}

/**
 * Business logic sits here
 */
interface T9nProcessor {

    /**
     * periodically check for stale transactions
     * and handle them
     */
    fun setupStaleChecks()

    /**
     * Create t9n in Database, fork it's processing and return t9n in it's initial state to the caller
     *
     * see Db.createOutgoingTransaction for parameter description
     */
    fun CoroutineScope.makeT9n(
            externalId: T9nExternalId,
            fromUserId: UserId,
            fromAccountId: AccountId,
            toUserId: UserId,
            amount: Int
    ): Validated<Err, T9n>

}