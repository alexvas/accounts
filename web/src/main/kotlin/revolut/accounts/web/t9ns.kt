package revolut.accounts.web

import io.ktor.application.call
import io.ktor.locations.get
import io.ktor.locations.put
import io.ktor.response.respond
import io.ktor.routing.Route
import revolut.accounts.common.Db
import revolut.accounts.common.T9nId
import revolut.accounts.common.T9nProcessor
import revolut.accounts.common.UserId
import revolut.accounts.web.UserLocation.CreateT9nsLocation
import revolut.accounts.web.UserLocation.IncomingT9nsLocation
import revolut.accounts.web.UserLocation.OutgoingT9nsLocation
import java.util.*

fun Route.t9ns(db: Db, t9nProcessor: T9nProcessor) {

    get<IncomingT9nsLocation> {
        val refined = refine(it.userLocation.id(), it.last, it.limit)
        if (refined == null) {
            call.respond(BadRequest)
            return@get finish()
        }

        finalAnswer {
            db.incomingTransactions(refined.user, refined.last, it.limit)
        }
    }

    get<OutgoingT9nsLocation> {
        val refined = refine(it.userLocation.id(), it.last, it.limit)
        if (refined == null) {
            call.respond(BadRequest)
            return@get finish()
        }

        finalAnswer {
            db.outgoingTransactions(refined.user, refined.last, it.limit)
        }
    }

    /**
     * idempotent transaction creation
     */
    put<CreateT9nsLocation> {
        val fromUser = it.userLocation.id()
        val external = it.externalId()
        val fromAccount = it.fromAccountId()
        val toUser = it.toUserId()
        val amount = it.amount

        if (fromUser == null || external == null || fromAccount == null || toUser == null || amount <= 0) {
            call.respond(BadRequest)
            return@put finish()
        }

        finalAnswer {
            t9nProcessor.makeT9n(external, fromUser, fromAccount, toUser, amount)
        }
    }
}

private data class Refined(
        val user: UserId,
        val last: T9nId?,
        val limit: Int
)

private fun refine(user: UserId?, t9n: String, limit: Int): Refined? {
    if (user == null || limit <= 0) {
        return null
    }
    val last = if (t9n.isEmpty())
        null
    else try {
        UUID.fromString(t9n)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return Refined(user, last?.let { T9nId(it) }, limit)
}
