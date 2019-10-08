package revolut.accounts.dal

import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.UserId
import revolute.accounts.dal.jooq.enums.T9nState
import revolute.accounts.dal.jooq.tables.records.AccountsRecord
import revolute.accounts.dal.jooq.tables.records.T9nsRecord
import java.sql.Timestamp
import java.time.Instant

/**
 * convert from project's data models into jooq's data models and vice versa
 */

internal fun Instant?.convert(): Timestamp? = this?.epochSecond?.let { Timestamp(it) }

internal fun T9nsRecord.convert() = T9n(
        id = T9nId(this.id),
        state = this.state.convert(),
        externalId = T9nExternalId(this.externalId),
        fromUser = UserId(this.fromUser),
        toUser = UserId(this.toUser),
        fromAccount = AccountId(this.fromAccount),
        toAccount = AccountId(this.toAccount),
        amount = this.amount.toUInt(),
        created = this.created.toInstant(),
        modified = this.modified.toInstant()
)

internal fun T9nState.convert(): T9n.State = when (this) {
    T9nState.INITIATED -> T9n.State.INITIATED
    T9nState.DECLINED -> T9n.State.DECLINED
    T9nState.DEBITED -> T9n.State.DEBITED
    T9nState.OVERFLOW -> T9n.State.OVERFLOW
    T9nState.COMPLETED -> T9n.State.COMPLETED
}

internal fun AccountsRecord.convert() = Account(
        id = AccountId(this.id),
        userId = UserId(this.userId),
        amount = this.amount.toUInt()
)
