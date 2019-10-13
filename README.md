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

### How to run

First, compile and test the project. From project dir run: 

`./gradlew test assemble`

after that go into integration directory:

`cd integration`

and start project with

`../gradlew run`

or even

`../gradlew run --args='-h'`

to see command-line options