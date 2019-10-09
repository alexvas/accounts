package revolut.accounts.web

import io.ktor.locations.Location
import org.apache.logging.log4j.LogManager
import revolut.accounts.common.UserId
import java.util.*

@Location("{user}")
internal data class UserLocation(
        val user: String
) {
    fun id(): UserId? {
        val uuid = try {
            UUID.fromString(user)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return UserId(uuid)
    }

    @Location("accounts")
    internal data class AccountsLocation(
            val userLocation: UserLocation
    )
}

