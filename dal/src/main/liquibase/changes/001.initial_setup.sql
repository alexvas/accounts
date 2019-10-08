--liquibase formatted sql

--changeset aavasiljev:1 logicalFilePath:none splitStatements:false

-- only to import gen_random_uuid():
CREATE EXTENSION IF NOT EXISTS pgcrypto;

/**
 * N.B.
 *
 * Using UUID as ID of any entity as it scales horizontally.
 * On the other hand, one would have to invent some synthetic comparable ID to implement pagination.
 */

CREATE TABLE users
(
    id                    UUID NOT NULL PRIMARY KEY default gen_random_uuid(),
    settlement_account_id UUID NOT NULL -- an account to credit on incoming money transfer
);

CREATE TABLE accounts
(
    id          UUID NOT NULL PRIMARY KEY default gen_random_uuid(),
    user_id     UUID NOT NULL
        CONSTRAINT accounts_users_ref REFERENCES users DEFERRABLE,
    amount      INT -- for simplicity assume money amount is positive integer and currency is always the same
        CONSTRAINT accounts_non_negative_amount CHECK ( amount >= 0 )
);
CREATE INDEX accounts_users_key
    ON accounts (user_id);

/**
 * In the scenario of user-to-user money transfer the one who sends money identifies recipient first
 * e.g. by his or her phone (phone number excluded from user details for brevity) and then is 
 * immediately able to make a transaction. In this scenario one does not need to know other's 
 * settlement account number to initiate transfer. This in turn means user entity must possess a 
 * reference to their settlement account. So here a cyclic dependency is. Cyclic dependency is a bad 
 * thing, but we go for it in our case.
 */
ALTER TABLE users
    ADD CONSTRAINT users_settlement_account_ref FOREIGN KEY (settlement_account_id) REFERENCES accounts DEFERRABLE;
CREATE INDEX users_settlement_account_key
    ON users (settlement_account_id);

/**
 * Here are states that FSM is based on.
 *
 * The FSM defines all possible transaction lifecycles.
 * States and transitions between them compose an acyclic graph.
 *
         * INITIATED -> DECLINED or DEBITED
         * DEBITED  -> OVERFLOW or COMPLETED
 *
 * Every transition between states (step)
 * either atomically changes the transaction state (one row in the t9ns table)
 * or atomically changes both the transaction state and one of accounts (one row in the accounts table).
 */
CREATE TYPE T9N_STATE AS ENUM (
    'INITIATED',
    'DECLINED',
    'DEBITED',
    'OVERFLOW',
    'COMPLETED'
    );

CREATE TABLE t9ns
(
    id           UUID      NOT NULL PRIMARY KEY default gen_random_uuid(),
    state        T9N_STATE NOT NULL             default 'INITIATED',
    external_id  UUID      NOT NULL,
    from_user    UUID      NOT NULL
        CONSTRAINT t9ns_from_user_ref REFERENCES users DEFERRABLE,
    to_user      UUID      NOT NULL
        CONSTRAINT t9ns_to_user_ref REFERENCES users DEFERRABLE,
    from_account UUID      NOT NULL
        CONSTRAINT t9ns_from_accounts_ref REFERENCES accounts DEFERRABLE,
    to_account   UUID      NOT NULL
        CONSTRAINT t9ns_to_accounts_ref REFERENCES accounts DEFERRABLE,
    amount       INT
        CONSTRAINT t9ns_non_negative_amount CHECK ( amount >= 0 ),
    created      TIMESTAMP                      default now(),
    modified     TIMESTAMP                      default now(),

    UNIQUE (from_user, external_id)
);

CREATE INDEX t9ns_from_user_key
    ON t9ns (from_user);
CREATE INDEX t9ns_to_user_key
    ON t9ns (to_user);
CREATE INDEX t9ns_from_account_key
    ON t9ns (from_account);
CREATE INDEX t9ns_to_account_key
    ON t9ns (to_account);
CREATE INDEX t9ns_outgoing_pagination_idx
    ON t9ns (from_user, created, external_id);
CREATE INDEX t9ns_incoming_pagination_idx
    ON t9ns (to_user, created, external_id);

CREATE OR REPLACE FUNCTION on_update_set_modified_to_now() RETURNS TRIGGER
    LANGUAGE PLpgSQL
AS
$$
BEGIN
    NEW.modified = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trigger_t9n_modified
    BEFORE UPDATE
    ON t9ns
    FOR EACH ROW
EXECUTE PROCEDURE on_update_set_modified_to_now();

