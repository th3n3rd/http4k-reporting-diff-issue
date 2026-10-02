package io.github.th3n3rd

import io.kotest.matchers.string.shouldNotContain
import org.http4k.chaos.ChaoticHttpHandler
import org.http4k.client.JavaHttpClient
import org.http4k.core.HttpTransaction
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.events.AutoMarshallingEvents
import org.http4k.events.HttpEvent
import org.http4k.events.ProtocolEvent
import org.http4k.filter.ClientFilters
import org.http4k.filter.ResponseFilters
import org.http4k.format.Jackson
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import org.http4k.server.uri
import org.junit.jupiter.api.Test

class ReportingDiffIssueTests {

    private val logs = StringBuffer()
    private val events = AutoMarshallingEvents(Jackson) { logs.appendLine(it) }

    @Test
    fun `does not expose pii (functional server)`() {
        val upstream = FakeUpstream()
        val client = ClientFilters.SetBaseUriFrom(Uri.of("http://upstream"))
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(upstream)

        client(Request(GET, "/details/http4k"))

        logs.toString() shouldNotContain "http4k"
    }

    @Test
    fun `does not expose pii (embedded server)`() {
        val upstream = FakeUpstream().asServer(SunHttp(0)).start()
        val client = ClientFilters.SetBaseUriFrom(upstream.uri())
            .then(ResponseFilters.ReportHttpTransaction { events(PiiSafeOutgoing(it)) })
            .then(JavaHttpClient())

        client(Request(GET, "/details/http4k"))

        logs.toString() shouldNotContain "http4k"
    }

    class FakeUpstream : ChaoticHttpHandler() {
        override val app = routes(
            "/details/{pii}" bind GET to { Response(Status.OK) }
        )
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
