package revolut.accounts.web

import io.ktor.locations.Location
import revolut.accounts.common.UserId
import java.util.*

const val DEFAULT_PAGE_SIZE = 25

@Location("api/users/{user}")
internal data class UserLocation(
        val user: String = ""
) {
    fun id() = user.asUuid()?.let { UserId(it) }

    @Location("accounts")
    internal data class AccountsLocation(
            val userLocation: UserLocation
    )

    @Location("transactions/incoming")
    internal data class IncomingT9nsLocation(
            val userLocation: UserLocation,
            val last: String = "",
            val limit: Int = DEFAULT_PAGE_SIZE
    )
    @Location("transactions/outgoing")
    internal data class OutgoingT9nsLocation(
            val userLocation: UserLocation,
            val last: String = "",
            val limit: Int = DEFAULT_PAGE_SIZE
    )

}

private fun String.asUuid(): UUID? {
    return if (isEmpty())
        null
    else try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        null
    }
}