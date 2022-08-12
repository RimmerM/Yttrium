package com.rimmer.yttrium.server.http

import com.rimmer.yttrium.Context
import com.rimmer.yttrium.Task
import com.rimmer.yttrium.getOrAdd
import com.rimmer.yttrium.parseInt
import com.rimmer.yttrium.server.ServerContext
import io.netty.buffer.ByteBuf
import io.netty.channel.EventLoop
import io.netty.handler.codec.http.*
import io.netty.util.AsciiString
import java.io.IOException
import java.util.*

class HttpPooledClient(
    context: ServerContext,
    val maxIdle: Long = 60L * 1000L * 1000000L,
    val maxBusy: Long = 60L * 1000L * 1000000L,
    val maxRetries: Int = 1,
    val debug: Boolean = false,
    val useNative: Boolean = false,
    val maxItems: Int = 4
) {
    private val pools: Map<EventLoop, HashMap<String, SingleThreadPool>>

    init {
        // We initialize the pool at creation to avoid synchronization when lazily creating pools.
        val map = HashMap<EventLoop, HashMap<String, SingleThreadPool>>()
        context.handlerGroup.forEach { loop ->
            if(loop is EventLoop) {
                map[loop] = HashMap()
            }
        }

        pools = map
    }

    fun get(context: Context, url: String, headers: HttpHeaders? = null, listener: HttpListener? = null) =
        request(context, url, HttpMethod.GET, headers, listener = listener)

    fun post(
        context: Context,
        url: String,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ) = request(context, url, HttpMethod.POST, headers, body, contentType, listener)

    fun put(
        context: Context,
        url: String,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ) = request(context, url, HttpMethod.PUT, headers, body, contentType, listener)

    fun delete(
        context: Context,
        url: String,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ) = request(context, url, HttpMethod.DELETE, headers, body, contentType, listener)

    fun request(
        context: Context,
        url: String,
        method: HttpMethod,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ): Task<HttpResult> {
        val loop = context.eventLoop
        val pool = pools[loop] ?:
            throw IllegalArgumentException("Invalid context event loop - the event loop must be in the server's handlerGroup.")

        val split = url.split("://")
        val isSsl: Boolean

        val index = if(split.size > 1) {
            isSsl = split[0] == "https"
            1
        } else {
            isSsl = false
            0
        }

        val domain = split[index].substringBefore('/')
        val path = split[index].substring(domain.length)

        val portString = domain.substringAfter(':', "")
        val host = if(portString.isEmpty()) domain else domain.substringBefore(':')
        val port = if(portString.isNotEmpty()) parseInt(portString) else if(isSsl) 443 else 80

        val selector = "$domain $isSsl"

        val request = genRequest(path, host, method, headers, body, contentType)
        val client = pool.getOrAdd(selector) { SingleThreadPool(PoolConfiguration(maxItems, maxIdle, maxBusy, debug = debug)) {
            connectHttp(loop, host, port, isSsl, 30000, useNative, it)
        } }

        val task = Task<HttpResult>()
        client.get { c, e ->
            if(e == null) {
                val wrappedListener = object : HttpListener {
                    var connection = c!!
                    var hasResult = false
                    var retries = 0

                    override fun onResult(result: HttpResult): Boolean {
                        hasResult = true
                        return listener?.onResult(result) ?: true
                    }

                    override fun onContent(result: HttpResult, content: ByteBuf, finished: Boolean): Boolean {
                        hasResult = true
                        val ret = listener?.onContent(result, content, finished) ?: true
                        if(finished || !ret) {
                            if(body is ByteBuf) body.release()
                            connection.close()
                            task.finish(result)
                        }

                        return ret
                    }

                    override fun onError(error: Throwable) {
                        connection.close()
                        if(hasResult) {
                            if(body is ByteBuf) body.release()
                            listener?.onError(error)
                            task.fail(error)
                        } else if(error is IOException && retries < maxRetries) {
                            if(debug) println("Retrying $method $path")

                            retries++
                            if(body is ByteBuf) body.retain()

                            client.get { c, e ->
                                if(e == null) {
                                    connection = c!!
                                    val req = genRequest(path, host, method, headers, body, contentType)
                                    connection.request(req, body, this)
                                } else {
                                    if(body is ByteBuf) body.release(body.refCnt())
                                    task.fail(e)
                                }
                            }
                        } else {
                            if(body is ByteBuf) body.release()
                            task.fail(error)
                        }
                    }
                }
                c!!.request(request, body, wrappedListener)
            } else {
                if(body is ByteBuf) body.release(body.refCnt())
                task.fail(e)
            }
        }
        return task
    }

    fun genRequest(
        path: String,
        domain: String,
        method: HttpMethod,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON
    ): HttpRequest {
        val request = if(body === null) {
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path)
        } else {
            DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path)
        }

        val httpHeaders = request.headers()
        if(headers !== null) {
            httpHeaders.add(headers)
        }

        if(!httpHeaders.contains(HttpHeaderNames.HOST)) {
            httpHeaders[HttpHeaderNames.HOST] = domain
        }

        if(body !== null) {
            if(!httpHeaders.contains(HttpHeaderNames.CONTENT_TYPE) && contentType != null) {
                httpHeaders[HttpHeaderNames.CONTENT_TYPE] = contentType
            }

            if(!httpHeaders.contains(HttpHeaderNames.CONTENT_LENGTH) && body is ByteBuf) {
                httpHeaders[HttpHeaderNames.CONTENT_LENGTH] = body.writerIndex()
            }
        }

        // Retain body in case we need to retry.
        if(body is ByteBuf) body.retain()

        return request
    }
}
