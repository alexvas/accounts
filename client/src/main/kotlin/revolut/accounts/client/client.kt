package revolut.accounts.client


import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import org.apache.commons.lang3.reflect.TypeUtils.parameterize
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import revolut.accounts.common.Account
import revolut.accounts.common.AccountId
import revolut.accounts.common.ApiClient
import revolut.accounts.common.Err
import revolut.accounts.common.ErrCode
import revolut.accounts.common.Invalid
import revolut.accounts.common.T9n
import revolut.accounts.common.T9nExternalId
import revolut.accounts.common.T9nId
import revolut.accounts.common.UserId
import revolut.accounts.common.Valid
import revolut.accounts.common.Validated
import java.lang.reflect.Type
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The class below corresponds to serialized form of Validated, i.e. either Valid or Invalid
 * For the former ok filed would be set and for the latter err would be set
 */
private data class ValidatedWrapper<T>(
        val ok: T?,
        val err: Err?
)

class ApiHttpClient(
        private val httpClient: HttpClient,
        private val urlBuilder: URLBuilder
) : ApiClient {

    override suspend fun accounts(user: UserId): Validated<List<Account>> = httpClient.typedCall(
            tType = parameterize(List::class.java, Account::class.java),
            method = HttpMethod.Get,
            path = "api/users/${user.id}/accounts"
    )

    override suspend fun outgoingT9ns(user: UserId, last: T9nId?, limit: Int?) = t9nList(
            "api/users/${user.id}/transactions/outgoing",
            last,
            limit
    )

    override suspend fun incomingT9ns(user: UserId, last: T9nId?, limit: Int?) = t9nList(
            "api/users/${user.id}/transactions/incoming",
            last,
            limit
    )

    override suspend fun createT9n(
            user: UserId,
            external: T9nExternalId,
            fromAccount: AccountId,
            recipient: UserId,
            amount: Int
    ): Validated<T9n> {
        require(amount > 0) { "amount must be positive, got $amount" }

        return httpClient.typedCall(
                tType = T9n::class.java,
                method = HttpMethod.Put,
                path = "api/users/${user.id}/transactions/create"
        )
    }

    private suspend fun t9nList(path: String, last: T9nId?, limit: Int?): Validated<List<T9n>> {
        require(limit == null || limit > 0) { "limit must be either null or positive, got $limit" }

        return httpClient.typedCall(
                tType = parameterize(List::class.java, T9n::class.java),
                method = HttpMethod.Get,
                path = path,
                params = ParametersBuilder().apply {
                    last?.let { this["last"] = it.id.toString() }
                    limit?.let { this["limit"] = it.toString() }
                }
        )
    }

    private suspend fun HttpResponse.handleResponse(): Validated<String> =
            if (status == HttpStatusCode.OK && contentType() == ContentType.Application.Json) try {
                Valid(readText(charset = Charsets.UTF_8))
            } catch (e: RuntimeException) {
                Invalid(ErrCode.CALL_ERROR, dumpDetails("unable to read (${e.message})"))
            } else
                Invalid(ErrCode.CALL_ERROR, dumpDetails("unexpected"))

    private suspend fun HttpResponse.dumpDetails(cause: String): String {
        val details = mutableListOf<String>()
        details += "$cause server"
        details += "response status = $status"
        details += "response contentType = ${contentType()}"
        try {
            val responseText = readText(charset = Charsets.UTF_8)
            if (responseText.isNotBlank()) {
                details += "response text $responseText"
            }
        } catch (ignored: RuntimeException) {
        }
        details += "request url = ${call.request.url}"
        details += "request method = ${call.request.method}"
        val content = call.request.content
        if (content is TextContent) {
            details += "request content type ${content.contentType}"
            details += "request content length ${content.contentLength}"
            details += "request content text ${content.text}"
        }
        return details.joinToString(", ")
    }

    private suspend fun HttpClient.plainTextCall(builder: HttpRequestBuilder): Validated<String> {
        call(builder).use {
            return it.response.handleResponse()
        }
    }

    private class InstantDeserializer : JsonDeserializer<Instant> {
        override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?) =
                json.takeIf { json != JsonNull.INSTANCE }?.let {
                    Instant.from(DateTimeFormatter.ISO_INSTANT.parse(it.asString))
                }

    }

    private val mapper = GsonBuilder()
            .registerTypeAdapter(Instant::class.java, InstantDeserializer())
            .create()

    /**
     * make a coll to API server
     *
     * @param tType - reflection token, a Type of generic parameter T
     * @param method - GET, PUT, etc.
     * @param path - URL path to the REST endpoint
     * @param params - http parameters to the REST query
     */
    private suspend fun <T> HttpClient.typedCall(
            tType: Type,
            method: HttpMethod,
            path: String,
            params: ParametersBuilder = ParametersBuilder()
    ): Validated<T> {
        val builder = HttpRequestBuilder().apply {
            url {
                takeFrom(urlBuilder)
                path(path)
                parameters.appendAll(params.build())
            }
            contentType(ContentType.Application.Json)
            this.method = method
        }
        val plainText = when (val res = plainTextCall(builder)) {
            is Invalid -> return res
            is Valid -> res.ok
        }
        val validatedWrapper = try {
            mapper.fromJson<ValidatedWrapper<T>>(plainText, parameterize(ValidatedWrapper::class.java, tType))
        } catch (e: RuntimeException) {
            log.warn("invalid json from server '$plainText', path = $path")
            return Invalid(ErrCode.BAD_SERVER_RESPONSE, plainText)
        }
        val ok = validatedWrapper.ok
        val err = validatedWrapper.err

        return when {
            ok != null -> Valid(ok)
            err != null -> Invalid(err.code, err.msg)
            else -> Invalid(ErrCode.BAD_SERVER_RESPONSE, "empty response from server")
        }
    }

    private companion object {
        val log: Logger = LogManager.getLogger()
    }

}


