package revolut.accounts.dal

import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.T9n
import revolut.accounts.common.T9n.State.COMPLETED
import revolut.accounts.common.T9n.State.DEBITED
import revolut.accounts.common.T9n.State.DECLINED
import revolut.accounts.common.T9n.State.INITIATED
import revolut.accounts.common.T9n.State.OVERFLOW
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.User
import revolut.accounts.common.UserId
import revolute.accounts.dal.jooq.enums.T9nState
import revolute.accounts.dal.jooq.tables.records.AccountsRecord
import revolute.accounts.dal.jooq.tables.records.T9nsRecord
import revolute.accounts.dal.jooq.tables.records.UsersRecord
import java.sql.Timestamp
import java.time.Instant

/**
 * convert from project's data models into jooq's data models and vice versa
 */

internal fun Instant?.convert(): Timestamp? = this?.let { Timestamp.from(it) }

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
    T9nState.INITIATED -> INITIATED
    T9nState.DECLINED -> DECLINED
    T9nState.DEBITED -> DEBITED
    T9nState.OVERFLOW -> OVERFLOW
    T9nState.COMPLETED -> COMPLETED
}

internal fun T9n.State.convert(): T9nState = when (this) {
    INITIATED -> T9nState.INITIATED
    DECLINED ->  T9nState.DECLINED
    DEBITED -> T9nState.DEBITED
    OVERFLOW -> T9nState.OVERFLOW
    COMPLETED -> T9nState.COMPLETED
}

internal fun AccountsRecord.convert() = Account(
        id = AccountId(this.id),
        userId = UserId(this.userId),
        amount = this.amount.toUInt(),
        settlement = this.settlement
)

internal fun UsersRecord.convert() = User(
        id = UserId(id)
)
