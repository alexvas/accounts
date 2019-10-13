package revolut.accounts.whiteBox

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.ajalt.clikt.core.NoRunCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.application.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.http.URLBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.http.impl.nio.reactor.IOReactorConfig
import revolut.accounts.client.ApiHttpClient
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.ApiClient
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.User
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import revolut.accounts.core.T9nProcessorImpl
import revolut.accounts.dal.Deps.db
import revolut.accounts.dal.Deps.dbInitializer
import revolut.accounts.dal.Deps.postgresPort
import revolut.accounts.web.module
import java.util.*
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextInt

val t9nProcessor = T9nProcessorImpl(db)

fun Application.module() = module(db, t9nProcessor)

fun main(args: Array<String>) {
    t9nProcessor.setupStaleChecks()

    val cmd = Cmd()
    cmd.main(args)

    val stress = Stress(cmd)
    stress.run()

    t9nProcessor.cancel()
    stress.cancel()
}

class Cmd : NoRunCliktCommand() {

    val host: String by option(help = "host to start REST API").default("localhost")
    val port: Int by option(help = "port to start REST API").int().default(8282)
    val iterationsCount: Int by option(
            help = "how many transactions (money transfers) to initiate",
            names = *arrayOf("--iterations_count")
    ).int().default(200)
    val initialAmount: Int by option(
            help = "an amount of money one of users would possess in his or her account",
            names = *arrayOf("--initial_amount")
    ).int().default(1_000_000)
    val maxTransferAmount: Int by option(
            help = "upper bond of initiated transfer random amount",
            names = *arrayOf("--max_transfer_amount")
    ).int().default(500_000)
}

class Stress(private val cmd: Cmd) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Job()

    fun run() {
        startApiBackend()
        val ledger = createLedger()

        runBlocking {
            val jobs = initMoneyTransfer(ledger)
            waitEveryT9nFinish(ledger, jobs)
        }

        checkResults(ledger)

        println("""
            database is still up and running. Try to check final state yourself with e.g.:
            echo 'select from_user, state, count(*), sum(amount) from t9ns group by 1,2 order by 1,2,3,4;' | psql -h 127.0.0.1 -p $postgresPort -U postgres
            """.trimIndent())
    }

    private fun CoroutineScope.initMoneyTransfer(ledger: Ledger): List<Job> {
        val rand = Random.Default

        print("initiate ${cmd.iterationsCount} money transfers: ")
        val part = cmd.iterationsCount / 100

        val jobs = mutableListOf<Job>()
        repeat(cmd.iterationsCount) {
            if (it % part == 0) print(".")
            val amount = rand.nextInt(1..cmd.maxTransferAmount)
            val t9nExternalId = T9nExternalId(UUID.randomUUID())
            jobs += launch(Dispatchers.Default) {
                val res = if (rand.nextBoolean())
                    createT9nOk(
                            t9nExternalId,
                            ledger.alice,
                            if (rand.nextBoolean()) ledger.aliceAccount1 else ledger.aliceAccount2,
                            ledger.bob,
                            amount
                    )
                else
                    createT9nOk(t9nExternalId, ledger.bob, ledger.bobAccount, ledger.alice, amount)
                when(res) {
                    is Valid -> Unit
                    is Invalid -> println("failed to init money transfer $it: ${res.err}")
                }
            }
        }
        println()
        println("all ${cmd.iterationsCount} API requests are launched")
        return jobs
    }

    private suspend fun waitEveryT9nFinish(ledger: Ledger, jobs: List<Job>) {
        println("processing")
        var count = 0
        while (true) {
            val stateCount = currentT9nStateCount(ledger)
            val currentStates = stateCount.keys
            if ((currentStates - T9n.State.COMPLETED - T9n.State.DECLINED).isNotEmpty() || stateCount.asSequence().sumBy { it.value } < jobs.size) {
                if (++count % 10 == 0) // report once in a second...
                    report("in progress", stateCount, jobs)
                delay(100)
            } else {
                // or immediately in case everything is done
                report("done", stateCount, jobs)
                break
            }
        }
    }

    private fun currentT9nStateCount(ledger: Ledger): Map<T9n.State, Int> {
        val allT9ns = ledger.alice.outgoing() + ledger.alice.incoming()
        return allT9ns.asSequence().map { it.state }.groupBy { it }.mapValues { e -> e.value.size }
    }

    private fun report(tagLine: String, stateCount: Map<T9n.State, Int>, jobs: List<Job>) {
        println("$tagLine:")
        stateCount.keys.sorted().forEach {
            println("$it => ${stateCount[it]}")
        }
        val activeJobs = jobs.count { it.isActive }
        println("$activeJobs of ${jobs.size} requests are still active")
        println()
    }

    private fun checkResults(ledger: Ledger) {
        val alice1 = ledger.alice.settlement().amount
        val alice2 = ledger.alice.findAccount(ledger.aliceAccount2.id).amount
        val bob1 = ledger.bob.settlement().amount

        check(alice1 + alice2 + bob1 == cmd.initialAmount) { "Never loose money" }

        // this is a probabilistic assumption:
        // in the result of random money transfers
        // it distributes between all three accounts
        check(alice2 > 0) { "The assert might fail when number of iterations is really high." }
        check(alice1 > 0) { "The assert might fail when number of iterations is low." }
        check(bob1 > 0) { "The assert might fail, yet it is very improbable." }
        println("final state OK: the sum is still ${cmd.initialAmount}, so no money is lost")
    }

    private var clientThreadNum = 0

    private val clientThreadFactory = ThreadFactory { Thread(it, "Ktor-stress-test-${++clientThreadNum}").apply { isDaemon = true } }
    private fun httpClient(iterationsCount: Int) = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = JacksonSerializer {
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
            }
        }

        engine {
            connectTimeout = 1_000_000
            socketTimeout = 1_000_000
            connectionRequestTimeout = 2_000_000
            customizeClient {
                setThreadFactory(clientThreadFactory)
                setDefaultIOReactorConfig(
                        IOReactorConfig.custom().apply {
                            setMaxConnPerRoute(iterationsCount)
                            setMaxConnTotal(iterationsCount)
                            setIoThreadCount((Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1))
                        }.build()
                )

            }
        }
    }

    private fun createLedger(): Ledger {
        val alice = dbInitializer.createUser()
        val bob = dbInitializer.createUser()

        val aliceAccount1 = alice.settlement()
        val aliceAccount2 = alice.addAccount(cmd.initialAmount)

        val bobAccount = bob.settlement()

        println("""
        Users ALICE=${alice.id.id} and BOB=${bob.id.id} created. 
        Alice initially possess ${cmd.initialAmount} in the account ${aliceAccount2.id.id}
        """.trimIndent())

        return Ledger(alice, aliceAccount1, aliceAccount2, bob, bobAccount)
    }

    private fun startApiBackend(): NettyApplicationEngine = embeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>(
            factory = Netty,
            host = cmd.host,
            port = cmd.port,
            module = Application::module
    ).start(wait = false)

    private val apiClient: ApiClient = ApiHttpClient(
            httpClient(cmd.iterationsCount),
            URLBuilder(host = cmd.host, port = cmd.port)
    )

    private suspend fun createT9nOk(
            externalId: T9nExternalId,
            fromUser: User,
            fromAccount: Account,
            toUser: User,
            amount: Int
    ): Validated<T9n> = apiClient.createT9n(fromUser.id, externalId, fromAccount.id, toUser.id, amount)
}

internal fun settlement(userId: UserId): Account {
    val result = db.accounts(userId)
    val accounts = (result as Valid).ok
    val filtered = accounts.filter { it.settlement }
    return filtered[0]
}

internal fun User.settlement() = settlement(id)

internal fun User.addAccount(amount: Int) = dbInitializer.createAccount(this, amount)

internal fun User.findAccount(id: AccountId): Account {
    val result = db.accounts(this.id)
    val accounts = (result as Valid).ok
    val filtered = accounts.filter { it.id == id }
    return filtered[0]
}

internal fun User.outgoing(last: T9nId?, limit: Int): List<T9n> {
    val result = db.outgoingTransactions(this.id, last, limit)
    return (result as Valid).ok
}

internal fun User.outgoing() = outgoing(null, Int.MAX_VALUE)

internal fun User.incoming(last: T9nId?, limit: Int): List<T9n> {
    val result = db.incomingTransactions(this.id, last, limit)
    return (result as Valid).ok
}

internal fun User.incoming() = incoming(null, Int.MAX_VALUE)

private data class Ledger(
        val alice: User,
        val aliceAccount1: Account,
        val aliceAccount2: Account,
        val bob: User,
        val bobAccount: Account
)
