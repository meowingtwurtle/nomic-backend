
import com.sun.net.httpserver.HttpServer
import web.APIHandler
import java.net.InetSocketAddress

fun main(args: Array<String>) {
    val server = HttpServer.create(InetSocketAddress(4453), 0)
    server.createContext("/", APIHandler)
    server.start()
}