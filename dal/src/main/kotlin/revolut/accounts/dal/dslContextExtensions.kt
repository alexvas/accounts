package revolut.accounts.dal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import revolut.accounts.common.AccountId
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.User
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import revolut.accounts.dal.jooq.Tables.ACCOUNTS
import revolut.accounts.dal.jooq.Tables.T9NS
import revolut.accounts.dal.jooq.Tables.USERS
import revolut.accounts.dal.jooq.enums.T9nState
import revolut.accounts.dal.jooq.tables.records.AccountsRecord

internal fun DSLContext.findT9n(t9nId: T9nId?): Validated<T9n?> = Valid(
        t9nId
                ?.let {
                    selectFrom(T9NS)
                            .where(T9NS.ID.eq(it.id))
                            .fetchOne()
                            ?: return Invalid(ErrCode.T9N_NOT_FOUND, "no transaction found for $it")
                }
                ?.convert()
)

internal fun DSLContext.insertT9n(externalId: T9nExternalId, fromUserId: UserId, fromAccountId: AccountId, toUserId: UserId, amount: Int) =
        insertInto(
                T9NS,
                T9NS.EXTERNAL_ID,
                T9NS.FROM_USER,
                T9NS.FROM_ACCOUNT,
                T9NS.TO_USER,
                T9NS.TO_ACCOUNT,
                T9NS.AMOUNT
        )
                .select(
                        select(
                                DSL.inline(externalId.id),
                                DSL.inline(fromUserId.id),
                                DSL.inline(fromAccountId.id),
                                DSL.inline(toUserId.id),
                                ACCOUNTS.ID,
                                DSL.inline(amount)
                        )
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.USER_ID.eq(toUserId.id).and(ACCOUNTS.SETTLEMENT))
                )
                .returning()
                .fetchOne()

internal fun DSLContext.userExists(userId: UserId) =
        fetchExists(select().from(USERS).where(USERS.ID.eq(userId.id)))

internal fun DSLContext.updateT9nState(t9nId: T9nId, currentState: T9nState, targetState: T9nState): Boolean {
    return 1 == update(T9NS)
            .set(T9NS.STATE, targetState)
            .where(
                    T9NS.ID.eq(t9nId.id)
                            .and(
                                    // Even the current state is already guarded by the "... FOR NO KEY UPDATE" clause,
                                    // let's add the condition anyway to be on the safe side.
                                    T9NS.STATE.eq(currentState)
                            )
            )
            .execute()
}

internal fun DSLContext.newAccount(owner: User, settlement: Boolean = false, amount: Int = 0): AccountsRecord = newRecord(ACCOUNTS).apply {
    require(amount >= 0) {
        "when creating ${if (settlement) "" else "non"}settlement account for user $owner the amount $amount must be positive"
    }
    userId = owner.id.id
    this.settlement = settlement
    this.amount = amount
    store()
}