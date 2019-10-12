package revolut.accounts.web

import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.locations.get
import io.ktor.locations.put
import io.ktor.response.respond
import io.ktor.routing.Route
import revolut.accounts.common.Db
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9nId
import revolut.accounts.common.T9nProcessor
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import revolut.accounts.web.UserLocation.CreateT9nsLocation
import revolut.accounts.web.UserLocation.IncomingT9nsLocation
import revolut.accounts.web.UserLocation.OutgoingT9nsLocation
import java.util.*

fun Route.t9ns(db: Db, t9nProcessor: T9nProcessor) {

    get<IncomingT9nsLocation> {
        val refined = when (val res = refine(it.userLocation.id(), it.last, it.limit)) {
            is Invalid -> {
                call.respond(HttpStatusCode.BadRequest, badRequest(res.err))
                return@get finish()
            }
            is Valid -> res.ok
        }

        finalAnswer {
            db.incomingTransactions(refined.user, refined.last, it.limit)
        }
    }

    get<OutgoingT9nsLocation> {
        val refined = when (val res = refine(it.userLocation.id(), it.last, it.limit)) {
            is Invalid -> {
                call.respond(HttpStatusCode.BadRequest, badRequest(res.err))
                return@get finish()
            }
            is Valid -> res.ok
        }

        finalAnswer {
            db.outgoingTransactions(refined.user, refined.last, it.limit)
        }
    }

    /**
     * idempotent transaction creation
     */
    put<CreateT9nsLocation> {
        val external = it.externalId()
        val fromUser = it.userLocation.id()
        val fromAccount = it.fromAccountId()
        val recipient = it.recipient()
        val amount = it.amount

        var err: String? = null
        if (external == null) {
            err = "bad external ID"
        }
        if (fromUser == null) {
            err = "bad sender ID"
        }
        if (fromAccount == null) {
            err = "bad from account ID"
        }
        if (recipient == null) {
            err = "bad recipient ID"
        }
        if (amount <= 0) {
            err = "negative amount"
        }

        if (err != null) {
            call.respond(HttpStatusCode.BadRequest, badRequest(Err(ErrCode.BAD_REQUEST, err)))
            return@put finish()
        }

        check(external != null && fromUser != null && fromAccount != null && recipient != null)

        finalAnswer {
            t9nProcessor.makeT9n(external, fromUser, fromAccount, recipient, amount)
        }
    }
}

private data class Refined(
        val user: UserId,
        val last: T9nId?,
        val limit: Int
)

private fun refine(user: UserId?, t9n: String, limit: Int): Validated<Refined> {
    if (user == null) {
        return Invalid(ErrCode.BAD_REQUEST, "bad user ID")
    }
    if (limit <= 0) {
        return Invalid(ErrCode.BAD_REQUEST, "non-positive limit")
    }
    val last = if (t9n.isEmpty())
        null
    else try {
        UUID.fromString(t9n)
    } catch (e: IllegalArgumentException) {
        return Invalid(ErrCode.BAD_REQUEST, "bad last transaction ID")
    }
    return Valid(Refined(user, last?.let { T9nId(it) }, limit))
}
