package revolut.accounts.integration

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import revolut.accounts.core.T9nProcessorImpl
import revolut.accounts.dal.Deps.db
import revolut.accounts.dal.Deps.dbInitializer
import revolut.accounts.web.module

fun main(args: Array<String>) {

    val t9nProcessor = T9nProcessorImpl(db)
    t9nProcessor.setupStaleChecks()

    fun Application.module() = module(db, t9nProcessor)

    class Web : CliktCommand() {
        private val host: String by option(help = "host to start REST API").default("localhost")
        private val port: Int by option(help = "port to start REST API").int().default(8282)

        override fun run() {
            fillDatabase(host, port)

            embeddedServer(
                    factory = Netty,
                    host = host,
                    port = port,
                    module = Application::module
            ).start(wait = true)
        }
    }

    Web().main(args)
}

private fun fillDatabase(host: String, port: Int) {
    val alice = dbInitializer.createUser()
    val bob = dbInitializer.createUser()

    val amount = 100_000
    val account = dbInitializer.createAccount(bob, amount)
    val d = "$"
    println("""
        export ALICE=${alice.id.id}
        export BOB=${bob.id.id}
        # Bob initially possess $amount in the account:
        export ACCOUNT=${account.id.id}
        
        # get Alice's account info with:
        curl $host:$port/api/users/${d}ALICE/accounts
        
        # get Bob's account info with:
        curl $host:$port/api/users/${d}BOB/accounts
        
        # make a money transfer:
        curl -X PUT "$host:$port/api/users/${d}BOB/transactions/create?external=${d}(uuidgen)&from_account=${d}ACCOUNT&recipient=${d}ALICE&amount=50"
        
        # follow money transfer state:
        curl $host:$port/api/users/${d}BOB/transactions/outgoing
        # ...and after the state is COMPLETED, re-examine accounts of Alice and Bob
    """.trimIndent())

}
