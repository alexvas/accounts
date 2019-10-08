package revolut.accounts.dal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import revolut.accounts.common.AccountId
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import revolute.accounts.dal.jooq.Tables
import revolute.accounts.dal.jooq.enums.T9nState


internal fun DSLContext.findT9n(t9nId: T9nId?): Validated<Err, T9n?> = Valid(
        t9nId
                ?.let {
                    selectFrom(Tables.T9NS)
                            .where(Tables.T9NS.ID.eq(it.id))
                            .fetchOne()
                            ?: return invalid(ErrCode.T9N_NOT_FOUND, "no transaction found for $it")
                }
                ?.convert()
)

internal fun DSLContext.insertT9n(externalId: T9nExternalId, fromUserId: UserId, fromAccountId: AccountId, toUserId: UserId, amount: UInt) {
    insertInto(
            Tables.T9NS,
            Tables.T9NS.EXTERNAL_ID,
            Tables.T9NS.FROM_USER,
            Tables.T9NS.FROM_ACCOUNT,
            Tables.T9NS.TO_USER,
            Tables.T9NS.TO_ACCOUNT,
            Tables.T9NS.AMOUNT
    )
            .select(
                    select(
                            DSL.inline(externalId.id),
                            DSL.inline(fromUserId.id),
                            DSL.inline(fromAccountId.id),
                            DSL.inline(toUserId.id),
                            Tables.USERS.SETTLEMENT_ACCOUNT_ID,
                            DSL.inline(amount.toInt())
                    )
                            .from(Tables.USERS)
                            .where(Tables.USERS.ID.eq(fromUserId.id))
            )
}

internal fun DSLContext.userExists(userId: UserId) =
        fetchExists(select().from(Tables.USERS).where(Tables.USERS.ID.eq(userId.id)))

internal fun DSLContext.updateT9nState(t9nId: T9nId, currentState: T9nState, targetState: T9nState): Boolean {
    return 1 == update(Tables.T9NS)
            .set(Tables.T9NS.STATE, targetState)
            .where(
                    Tables.T9NS.ID.eq(t9nId.id)
                            .and(
                                    // Even the current state is already guarded by the "... FOR NO KEY UPDATE" clause,
                                    // let's add the condition anyway to be on the safe side.
                                    Tables.T9NS.STATE.eq(currentState)
                            )
            )
            .execute()
}
