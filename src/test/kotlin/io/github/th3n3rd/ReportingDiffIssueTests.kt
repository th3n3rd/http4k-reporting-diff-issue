package io.github.th3n3rd

import io.kotest.matchers.string.shouldNotContain
import org.http4k.chaos.ChaoticHttpHandler
import org.http4k.client.JavaHttpClient
import org.http4k.core.Filter
import org.http4k.core.HttpTransaction
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.events.AutoMarshallingEvents
import org.http4k.events.HttpEvent
import org.http4k.events.ProtocolEvent
import org.http4k.filter.ClientFilters
import org.http4k.filter.ResponseFilters
import org.http4k.format.Jackson
import org.http4k.kotest.shouldHaveStatus
import org.http4k.routing.RoutedMessage
import org.http4k.routing.bind
import org.http4k.routing.orElse
import org.http4k.routing.routes
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import org.http4k.server.uri
import org.junit.jupiter.api.Test

class ReportingDiffIssueTests {

    private val logs = StringBuffer()
    private val events = AutoMarshallingEvents(Jackson) { logs.appendLine(it) }

    @Test
    fun `does not expose pii (functional server as is)`() {
        val upstream = FakeUpstream()
        val client = ClientFilters.SetBaseUriFrom(Uri.of("http://upstream"))
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(DebugRoutingContext())
            .then(upstream)

        val response = client(Request(GET, "/details/http4k"))

        response shouldHaveStatus OK
        logs.toString() shouldNotContain "http4k"
    }

    @Test
    fun `does not expose pii (functional server with proxy clean)`() {
        val upstream = FakeUpstream()
        val client = ClientFilters.SetBaseUriFrom(Uri.of("http://upstream"))
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(DebugRoutingContext())
            .then(ClientFilters.CleanProxy())
            .then(upstream)

        val response = client(Request(GET, "/details/http4k"))

        response shouldHaveStatus OK
        logs.toString() shouldNotContain "http4k"
    }

    @Test
    fun `does not expose pii (functional server as routing)`() {
        val upstream = FakeUpstreamRouting()
        val client = ClientFilters.SetBaseUriFrom(Uri.of("http://upstream"))
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(DebugRoutingContext())
            .then(upstream)

        val response = client(Request(GET, "/details/http4k"))

        response shouldHaveStatus OK
        logs.toString() shouldNotContain "http4k"
    }

    @Test
    fun `does not expose pii (functional server wrapped as routing with else matcher)`() {
        val upstream = FakeUpstream()
        val client = ClientFilters.SetBaseUriFrom(Uri.of("http://upstream"))
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(DebugRoutingContext())
            .then(routes(orElse bind upstream))

        val response = client(Request(GET, "/details/http4k"))

        response shouldHaveStatus OK
        logs.toString() shouldNotContain "http4k"
    }

    @Test
    fun `does not expose pii (functional server wrapped as routing with catch-all path)`() {
        val upstream = FakeUpstream()
        val client = ClientFilters.SetBaseUriFrom(Uri.of("http://upstream"))
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(DebugRoutingContext())
            .then(routes("/{catch-all:.*}" bind upstream))

        val response = client(Request(GET, "/details/http4k"))

        response shouldHaveStatus OK
        logs.toString() shouldNotContain "http4k"
    }

    @Test
    fun `does not expose pii (embedded server)`() {
        val upstream = FakeUpstream().asServer(SunHttp(0)).start()
        val client = ClientFilters.SetBaseUriFrom(upstream.uri())
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(DebugRoutingContext())
            .then(JavaHttpClient())

        val response = client(Request(GET, "/details/http4k"))

        response shouldHaveStatus OK
        logs.toString() shouldNotContain "http4k"
    }

    class FakeUpstream : ChaoticHttpHandler() {
        override val app = routes(
            "/details/{pii}" bind GET to { Response(OK) }
        )
    }

    object FakeUpstreamRouting {
        operator fun invoke() = routes(
            "/details/{pii}" bind GET to { Response(OK) }
        )
    }

    object DebugRoutingContext {
        operator fun invoke() = Filter { next ->
            { request ->
                println("before:")
                println("request class = ${request::class}")
                println("request routed = ${request is RoutedMessage}")

                val response = next(request)

                println("after:")
                println("response class = ${response::class}")
                println("response routed = ${response is RoutedMessage}")
                if (response is RoutedMessage) {
                    println("response template = ${response.xUriTemplate}")
                }

                response
            }
        }
    }

    object PiiSafeOutgoing {
        operator fun invoke(tx: HttpTransaction): ProtocolEvent.Outgoing {
            val outgoing = HttpEvent.Outgoing(tx)
            return ProtocolEvent.Outgoing(
                uri = outgoing.uri.maskPii(),
                method = outgoing.method,
                status = outgoing.status,
                latency = outgoing.latency,
                xUriTemplate = outgoing.xUriTemplate, // left un-masked deliberately, e.g. a refactoring mistake
                protocol = outgoing.protocol,
            )
        }

        private fun Uri.maskPii(): Uri = Uri.of(toString().replace("http4k", "*".repeat(10)))
    }
}
