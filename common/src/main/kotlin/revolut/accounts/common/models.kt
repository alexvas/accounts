package revolut.accounts.common

import java.time.Instant
import java.util.*

inline class UserId(val id: UUID)

data class User(
        val id: UserId
        /*
         * Usually people have name, surname, middle name, honorific, gender, etc. --
         * all these details are omitted here for the sake of brevity
         */
)

inline class AccountId(val id: UUID)

data class Account(
        val id: AccountId,
        val userId: UserId,
        val amount: UInt
)

inline class T9nId(val id: UUID)
inline class T9nExternalId(val id: UUID)

data class T9n(
        val id: T9nId,
        val state: State,
        val externalId: T9nExternalId,
        val fromUser: UserId,
        val toUser: UserId,
        val fromAccount: AccountId,
        val toAccount: AccountId,
        val amount: UInt,
        val created: Instant,
        val modified: Instant
) {
        /**
         * Here are states that FSM based on
         *
         * The FSM defines all possible transaction lifecycles.
         * States and transitions between them compose an acyclic graph.
         *
         * INITIATED -> DECLINED or DEBITED
         * DEBITED  -> OVERFLOW or COMPLETED
         *
         */

        enum class State {
                INITIATED,
                DECLINED,
                DEBITED,
                OVERFLOW,
                COMPLETED,
        }
}