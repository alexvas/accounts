package revolut.accounts.web

import io.ktor.locations.get
import io.ktor.routing.Route
import revolut.accounts.common.Db
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.web.UserLocation.AccountsLocation

fun Route.accounts(db: Db) {

    get<AccountsLocation> {
        val userId = it.userLocation.id() ?: return@get finalAnswer(Invalid(ErrCode.BAD_REQUEST, "bad user ID"))

        finalAnswer {
            db.accounts(userId)
        }
    }
}