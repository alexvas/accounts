@file:JvmName("Main")

package revolut.accounts.web

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.DefaultHeaders
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Forbidden
import io.ktor.http.HttpStatusCode.Companion.InsufficientStorage
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.HttpStatusCode.Companion.PaymentRequired
import io.ktor.jackson.jackson
import io.ktor.locations.Locations
import io.ktor.response.respond
import io.ktor.routing.routing
import io.ktor.util.pipeline.PipelineContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.slf4j.event.Level
import revolut.accounts.common.Db
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode.ACCOUNT_NOT_FOUND
import revolut.accounts.common.ErrCode.BAD_REQUEST
import revolut.accounts.common.ErrCode.ENTITY_ALREADY_EXISTS
import revolut.accounts.common.ErrCode.FUNDS_OVERFLOW
import revolut.accounts.common.ErrCode.INSUFFICIENT_FUNDS
import revolut.accounts.common.ErrCode.INTERNAL
import revolut.accounts.common.ErrCode.OTHERS_ACCOUNT
import revolut.accounts.common.ErrCode.T9N_NOT_FOUND
import revolut.accounts.common.ErrCode.USER_NOT_FOUND
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9nProcessor
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import java.text.DateFormat

typealias Pc = PipelineContext<Unit, ApplicationCall>

fun Application.module(db: Db, t9nProcessor: T9nProcessor) {
    install(CallLogging) {
        level = Level.DEBUG
    }
    install(DefaultHeaders)
    install(Locations)
    install(StatusPages)
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
            dateFormat = DateFormat.getDateInstance()
            disableDefaultTyping()
            registerModule(JavaTimeModule())
        }
    }
    routing {
        trace { application.log.debug(it.buildText()) }
        accounts(db)
        t9ns(db, t9nProcessor)
    }
}

internal fun Any.logger(name: String): Logger = LogManager.getLogger(javaClass.`package`.name + "." + name)

private val log = object {}.logger("application")

private fun Err.httpStatusCode() = when (code) {
    INTERNAL -> InternalServerError
    USER_NOT_FOUND, ACCOUNT_NOT_FOUND, T9N_NOT_FOUND -> NotFound
    OTHERS_ACCOUNT, ENTITY_ALREADY_EXISTS -> Forbidden
    INSUFFICIENT_FUNDS -> PaymentRequired
    FUNDS_OVERFLOW -> InsufficientStorage
    BAD_REQUEST -> BadRequest
}

internal suspend fun Pc.finalAnswer(validated: Validated<*>) {
    call.respond(
            when (validated) {
                is Valid -> OK
                is Invalid -> validated.err.httpStatusCode()
            },
            validated
    )
    finish()
}

internal suspend fun Pc.finalAnswer(block: () -> Validated<*>) = finalAnswer((findAnswer(block)))

private fun findAnswer(block: () -> Validated<*>): Validated<*> {
    val result = try {
        block.invoke()
    } catch (t: Throwable) {
        log.error("unexpected error", t)
        return Invalid(INTERNAL, t.message ?: "")
    }

    if (result is Invalid) {
        val err = result.err
        when (err.code) {
            INTERNAL -> log.error("internal error: {}", err.msg)
            else -> log.info("request failed with code {}: {}", err.code, err.msg)
        }
    }

    return result
}
