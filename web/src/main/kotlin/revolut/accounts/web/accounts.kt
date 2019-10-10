package revolut.accounts.web

import io.ktor.application.call
import io.ktor.locations.get
import io.ktor.response.respond
import io.ktor.routing.Route
import revolut.accounts.common.Db
import revolut.accounts.web.UserLocation.AccountsLocation

fun Route.accounts(db: Db) {

    get<AccountsLocation> {
        val userId = it.userLocation.id()
        if (userId == null) {
            call.respond(BadRequest)
            return@get finish()
        }

        finalAnswer {
            db.accounts(userId)
        }
    }
}