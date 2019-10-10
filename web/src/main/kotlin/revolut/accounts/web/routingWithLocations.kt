package revolut.accounts.web

import io.ktor.locations.Location
import revolut.accounts.common.AccountId
import revolut.accounts.common.T9nExternalId
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
    @Location("transactions/create")
    internal data class CreateT9nsLocation(
            val userLocation: UserLocation,
            val external: String = "",
            val from_account: String = "",
            val recipient: String = "",
            val amount: Int = 0
    ) {
        fun externalId() = external.asUuid()?.let { T9nExternalId(it) }
        fun fromAccountId() = from_account.asUuid()?.let { AccountId(it) }
        fun recipient() = recipient.asUuid()?.let { UserId(it) }
    }
}

/**
 * In Kotlin one is able to use null as a kind of "soft error". Here it is:
 * returning UUID when input is OK or null otherwise
 */
private fun String.asUuid(): UUID? {
    return if (isEmpty())
        null
    else try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        null
    }
}