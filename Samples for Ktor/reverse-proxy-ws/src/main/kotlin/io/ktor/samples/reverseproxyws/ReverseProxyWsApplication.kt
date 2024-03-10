package io.ktor.samples.reverseproxyws

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.intellij.lang.annotations.*
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(WebSockets)
    routing {
        get("/") {
            @Language("es6")
            val js = """
                    const repliesDiv = document.getElementById('replies');
                    const messageInput = document.getElementById('message');
                    const sendMessageButton = document.getElementById('sendMessage');

                    function addReply(text) {
                        const div = document.createElement("div")
                        div.innerText = text;
                        repliesDiv.appendChild(div);
                    }

                    const ws = new WebSocket("ws://127.0.0.1:${call.request.origin.port}")
                    ws.onopen = (e) => { addReply("Connected"); };
                    ws.onclose = (e) => { addReply("Disconnected"); };
                    ws.onerror = (e) => { addReply("Error " + e); };
                    ws.onmessage = (e) => { addReply("Received: " + e.data); };

                    sendMessageButton.onclick = (e) => {
                        const message = messageInput.value;
                        messageInput.value = "";
                        ws.send(message);
                    };
                """.trimIndent()

            call.respondText(
                """
                    <html>
                        <body>
                            <form action="javascript:void(0)" method="post">
                                <input id="message" type="text" autofocus />
                                <input id="sendMessage" type="submit" value="Send" />
                            </form>
                            <pre id="replies"></pre>
                            <script type="text/javascript">$js</script>
                        </body>
                    </html>
                    """.trimIndent(),
                ContentType.Text.Html
            )
        }
        // webSocketReverseProxy("/", proxied = Url("wss://echo.websocket.org/?encoding=text")) // Not working (disconnecting)
        webSocketReverseProxy("/", proxied = Url("ws://echo.websocket.org/?encoding=text"))
    }
}

fun Route.webSocketReverseProxy(path: String, proxied: Url) {
    webSocket(path) {
        val serverSession = this

        val client = HttpClient(CIO).config {
            install(ClientWebSockets) {
            }
        }

        client.webSocket(call.request.httpMethod, proxied.host, 0, proxied.fullPath, request = {
            url.protocol = proxied.protocol
            url.port = proxied.port
            println("Connecting to: ${url.buildString()}")
        }) {
            val clientSession = this
            val serverJob = launch {
                serverSession.incoming.pipeTo(clientSession.outgoing)

                // // Or this:
                // for (received in serverSession.incoming) {
                //    clientSession.send(received)
                // }
            }

            val clientJob = launch {
                clientSession.incoming.pipeTo(serverSession.outgoing)

                // // Or this:
                // for (received in clientSession.incoming) {
                //    serverSession.send(received)
                // }
            }

            // clientSession.send(io.ktor.http.cio.websocket.Frame.Text("hello"))

            listOf(serverJob, clientJob).joinAll()
        }
    }
}

suspend fun <E> ReceiveChannel<E>.pipeTo(send: SendChannel<E>) = run { for (received in this) send.send(received) }
