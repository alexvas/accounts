package revolut.accounts.web

import io.ktor.application.call
import io.ktor.locations.get
import io.ktor.response.respond
import io.ktor.routing.Route
import revolut.accounts.common.Db
import revolut.accounts.common.Invalid
import revolut.accounts.common.Valid
import revolut.accounts.web.UserLocation.AccountsLocation

private val log = object {}.logger("accounts")

fun Route.accounts(db: Db) {

    get<AccountsLocation> {
        val userId = it.userLocation.id()
        if (userId == null) {
            call.respond(BadRequest)
            return@get finish()
        }
        val result = try {
            db.accounts(userId)
        } catch (t: Throwable) {
            log.error("unexpected error", t)
            call.respond(ServerError)
            return@get finish()
        }

        val response: Any = when(result) {
            is Invalid -> result.err.toResponse()
            is Valid -> result.value
        }
        call.respond(response)
        finish()
    }
}