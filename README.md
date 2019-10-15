### About

This is a **Backend Test** project for Revolut. Here is the task:

#### Backend Test

Design and implement a RESTful API (including data model and the backing implementation) for
money transfers between accounts.

##### Explicit requirements:

1. You can use Java or Kotlin.
1. Keep it simple and to the point (e.g. no need to implement any authentication).
1. Assume the API is invoked by multiple systems and services on behalf of end users.
1. You can use frameworks/libraries if you like (**​except Spring**​), but don't forget about
requirement #2 and keep it simple and avoid heavy frameworks.
1. The datastore should run in-memory for the sake of this test.
1. The final result should be executable as a standalone program (should not require a
pre-installed container/server).
1. Demonstrate with tests that the API works as expected.

##### Implicit requirements:
1. The code produced by you is expected to be of high quality.
1. There are no detailed requirements, use common sense.

**Please put your work on github or bitbucket.**

### How to run backend

First, compile and test the project. From project dir run: 

`./gradlew test assemble`

after that descend into app directory:

`cd app`

and start project with

`../gradlew run`

or even

`../gradlew run --args='-h'`

to see command-line options.

There would be a few bash commands in stdout to make API requests with a help of `curl` command-line utility.

### How to run stress-tests

The same way as backend only (see above). Just execute `cd white-box` instead of `cd app`. 
Look into command-line options. I was able to initiate 25000 connections to the backend at once and got
success as a result. 

### Vocabulary

Since the `transaction` term has so much meanings, including money transfer itself, I would try to avoid
using it as much as possible. Term `money transfer` is used the same way as in the initial home task
description, i.e. as an action on the scenario level of interaction between end-users and backend. 
Term `t9n` is used to denote an entity, corresponding to a single money transfer either on a level of 
application's business logic or on the level of database storage. Term `tx` is used to denote a database
transaction.

### The problem

Database atomicity is not enough to perform money transfer between two accounts, as naive approach would lead into
deadlock under heavy load. This faulty approach at the database level is the following:

1. open tx
1. lock a row in the accounts table corresponding to the sender's account
1. decrement the account for amount of transfer
1. lock a row in the accounts table corresponding to the recipient's account
1. increment the account for amount of transfer
1. close tx and release both locks

In the case the tx fails, both decrement and increment operations are cancelled as they did not happen. So the
balance is not changed, which is a good thing. Bad thing happens in the case of simultaneous mutual txes.
Suppose there are two mutual txes between Alice and Bob. One tx acquired a lock for the Alice's account as 
a sender and another tx acquired a lock for the Bob's account also as a sender. Then both will wait for
the receiver lock mutually blocking each other. Here is a deadlock.

The solution to the problem is well-known nowadays. There must exist an ordering in the process of lock 
acquisition. Say, Alice's account is always locked first and Bob's is locked after that. It the case of
two mutual txes one will acquire Alice's account lock and will finish. Another will wait until
the Alice's account lock is free, then acquire it and will also finish.

This hometask is a bit more complex, as there is another entity -- t9n table, that needs being updated. 
End-users would like to know the state of their txes e.g. either it succeeded or failed and keep transfer
list with their state for accounting or history purposes.

The implemented solution is to give up simultaneous update of all three entities: two rows in accounts 
table and one row in t9ns table. Let's split single money transfer action in stages and use additional 
tx states to ensure integrity of the process as a whole (see `debitSender` and `creditRecipient` 
operations on the database level). The order of lock acquisition is still important to avoid deadlocks.

The absence of deadlocks was tested here with concurrency as high as possible, yet this might not be enough,
as deadlock is a probabilistic thing. It might become apparent in a very rare case, but nevertheless 
must be eliminated. One might need to use appropriate tools to eliminate deadlocks in a really complex 
system, like e.g. TLA+. Yet constructing TLA+ formula of money transfer is out of the scope of the task.

### Idempotence

Failure is in the nature of network. There are some guarantees on http request delivery, yet it is not 
transactional per se. Both request and response might fail. The backend also might fail in between
and end-user might never know for certain what was happen: either their request was successfully executed
at the backend and he or she just did not get a report of success or their request failed at the start and
no intended money transfer happened. The end-user may want to repeat a transfer action in the situation
of failure, but what if primary request was successful? In that case end-user will pay double price.

To avoid situation of uncertainty, let's make money transfer idempotent. We'll use unique external id for 
that. In case backend will see the same external ID -- no need to bother. Let's return to the end-user 
result of the previous operation marked with the same external ID.       

### Architecture

The project is small, yet decoupling is of paramount importance. Divide et impera. The project consists of 7 modules: 
`common`, `dal`, `core`, `web`, `client`, `app` and `white-box`. `common` contains data models and common
interface definitions, all other modules depend on this one. `dal`, `core`, `web` and `client` depend 
_only_ on `common` and are completely independent from each other. `dal` interacts with database. 
`core` contains business logic. `web` contains HTTP REST backend and `client` -- a client for that 
endpoint. `app` depends on all other modules except `client` and `white-box`, it runs backend server. 
`white-box` depends on all other modules except `app`, it runs white-box integration tests. 
