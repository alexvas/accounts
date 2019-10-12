package revolut.accounts.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import revolut.accounts.common.AccountId
import revolut.accounts.common.Db
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nProcessor
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.coroutines.CoroutineContext

private val durationToBecomeStale: Duration = Duration.of(10, ChronoUnit.SECONDS)
private const val MAX_BATCH_SIZE = 100

class T9nProcessorImpl(private val db: Db) : T9nProcessor {
    override val coroutineContext: CoroutineContext
        get() = Job()

    override fun setupStaleChecks() {
        launch {
            while (true) {
                delay(durationToBecomeStale.toMillis())
                staleCheck()
            }
        }
    }

    private fun staleCheck() {
        db.staleInitiated(durationToBecomeStale, MAX_BATCH_SIZE).forEach {
            debitSender(it)
        }
        db.staleDebited(durationToBecomeStale, MAX_BATCH_SIZE).forEach {
            creditRecipient(it)
        }
    }

    override fun makeT9n(
            externalId: T9nExternalId,
            fromUserId: UserId,
            fromAccountId: AccountId,
            toUserId: UserId,
            amount: Int

    ): Validated<T9n> {
        if (!db.checkIfAccountBelongsToUser(fromAccountId, fromUserId))
            return Invalid(ErrCode.OTHERS_ACCOUNT, "account ID $fromAccountId does not belong to the user with ID $fromUserId")

        val t9n = when (
            val result = db.createOutgoingTransaction(
                    externalId, fromUserId, fromAccountId, toUserId, amount
            )
            ) {
            is Invalid -> return result
            is Valid -> result.ok
        }

        launch {
            debitSender(t9n)
        }

        return Valid(t9n)
    }

    private fun debitSender(t9n: T9n) {
        when (val debited = db.debitSender(t9n.id)) {
            is Invalid -> {
                log.error("debiting of {} failed with {}", t9n, debited.err)
            }
            is Valid -> {
                if (debited.ok) {
                    creditRecipient(t9n)
                }
            }
        }
    }

    private fun creditRecipient(t9n: T9n) {
        val credited = db.creditRecipient(t9n.id)
        if (credited is Invalid) log.error("crediting of {} failed with {}", t9n, credited.err)
    }

    private companion object {
        val log: Logger = LogManager.getLogger()
    }

}

