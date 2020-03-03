package com.rimmer.yttrium.server.http

import com.rimmer.yttrium.ByteString
import com.rimmer.yttrium.byteBuf
import com.rimmer.yttrium.serialize.JsonWriter
import com.rimmer.yttrium.serialize.Writable
import com.rimmer.yttrium.serialize.writeJson
import com.rimmer.yttrium.server.connect
import com.rimmer.yttrium.string
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.EventLoopGroup
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.multipart.DiskAttribute
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder
import io.netty.handler.codec.http.multipart.MemoryAttribute
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream

class HttpResult(val status: HttpResponseStatus, val statusCode: Int, val headers: HttpHeaders)

/** Handles the result of an HTTP request. */
interface HttpListener {
    /**
     * Receives the initial response headers.
     * This is called once, before onContent.
     * @return True if the client should receive the response body. False if the download should be stopped.
     */
    fun onResult(result: HttpResult): Boolean {
        return true
    }

    /**
     * Receives response data. This is called at least once.
     * This can be called multiple times with chunks of the response.
     * The content buffer is released after this.
     * @param finished If set, this is the last chunk and request has finished.
     * @return True if the client should keep receiving content. False if the download should be stopped.
     */
    fun onContent(result: HttpResult, content: ByteBuf, finished: Boolean): Boolean {
        return true
    }

    /**
     * Called when an error occurs.
     * This can happen even after onResponse or onContent has been called,
     * but not once the request has finished.
     */
    fun onError(error: Throwable) {}
}

/** Http listener that appends to a buffer. */
open class BufferListener(val buffer: ByteBuf): HttpListener {
    override fun onContent(result: HttpResult, content: ByteBuf, finished: Boolean): Boolean {
        buffer.writeBytes(content)
        return true
    }
}

/** Http listener that appends to a string. */
open class StringListener(val string: StringBuilder): HttpListener {
    override fun onContent(result: HttpResult, content: ByteBuf, finished: Boolean): Boolean {
        string.append(content.string)
        return true
    }
}

interface HttpClient {
    fun request(request: HttpRequest, body: Any?, listener: HttpListener)
    fun request(method: HttpMethod, path: String, listener: HttpListener)
    fun request(method: HttpMethod, path: String, body: Any, listener: HttpListener)
    fun request(method: HttpMethod, path: String, body: Array<Pair<String, Any>>, listener: HttpListener)

    /** Closes this connection. Any calls after this will fail. */
    fun close()

    /** Set to true as long as the connection is active. */
    val connected: Boolean

    /** Set to true if the connection is currently waiting for a response. */
    val busy: Boolean

    /** The amount of time this connection has been querying since the query start. If idle, this returns 0. */
    val busyTime: Long

    /** The amount of time this connection has been idle since the last action. */
    val idleTime: Long
}

fun connectHttp(
    loop: EventLoopGroup,
    host: String, port: Int,
    ssl: Boolean = false,
    timeout: Int = 0,
    useNative: Boolean = false,
    f: (HttpClient?, Throwable?) -> Unit
) {
    connect(loop, host, port, timeout, useNative, {
        val sslContext = if(ssl) {
            val sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            val handler = SslHandler(sslContext.newEngine(ByteBufAllocator.DEFAULT, host, port))
            addLast(handler)
            handler
        } else null

        addLast(
            HttpResponseDecoder(),
            HttpRequestEncoder(),
            HttpContentDecompressor(),
            HttpClientHandler(f, sslContext)
        )
    }, {
        f(null, it)
    })
}

class HttpClientHandler(var onConnect: ((HttpClient?, Throwable?) -> Unit)?, private var ssl: SslHandler?): ChannelInboundHandlerAdapter(), HttpClient {
    private var context: ChannelHandlerContext? = null
    private var listener: HttpListener? = null
    private var result: HttpResult? = null
    private var requestStart = 0L
    private var requestEnd = System.nanoTime()
    private var queueClose = false

    override val connected: Boolean get() = !queueClose && context != null && context!!.channel().isActive
    override val busy: Boolean get() = listener != null

    override val busyTime: Long
        get() = if(requestStart > 0) System.nanoTime() - requestStart else 0

    override val idleTime: Long
        get() = if(requestStart > 0) 0L else System.nanoTime() - requestEnd

    override fun channelActive(context: ChannelHandlerContext) {
        this.context = context

        val ssl = this.ssl
        if(ssl == null) {
            onConnect?.invoke(this, null)
            onConnect = null
        } else {
            ssl.handshakeFuture().addListener {
                if(it.isSuccess) {
                    onConnect?.invoke(this, null)
                } else {
                    onConnect?.invoke(null, it.cause())
                }
                onConnect = null
            }
        }
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        // Fail any pending request.
        val error = IOException("Connection was closed.")
        queueClose = true

        this.context = null
        ssl?.closeOutbound()
        ssl = null

        listener?.onError(error)
        listener = null
    }

    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        val listener = listener
        if(message is HttpResponse) {
            val result = HttpResult(message.status(), message.status().code(), message.headers())
            this.result = result

            if(listener?.onResult(result) == false) {
                onFinish()
                doClose()
            } else if(result.headers.get(HttpHeaderNames.CONNECTION)?.toLowerCase() == "close") {
                queueClose = true
            }
        } else if(message is HttpContent) {
            val result = this.result
            val finished = if(message is LastHttpContent) {
                onFinish()
                true
            } else false

            if(listener?.onContent(result!!, message.content(), finished) == false) {
                onFinish()
                doClose()
            }

            message.content().release()

            if(finished && queueClose) doClose()
        }
    }

    override fun request(request: HttpRequest, body: Any?, listener: HttpListener) {
        onRequest(listener)
        val context = context!!
        val contentLength: Int

        val content = when(body) {
            is ByteBuf -> {
                contentLength = body.writerIndex()
                DefaultLastHttpContent(body)
            }
            is String -> {
                val buffer = body.byteBuf
                contentLength = buffer.writerIndex()
                DefaultLastHttpContent(buffer)
            }
            is ByteString -> {
                val buffer = ByteBufAllocator.DEFAULT.buffer(body.size)
                body.write(buffer)
                contentLength = buffer.writerIndex()
                DefaultLastHttpContent(buffer)
            }
            is Writable -> {
                val buffer = ByteBufAllocator.DEFAULT.buffer()
                body.encodeJson(JsonWriter(buffer))
                contentLength = buffer.writerIndex()
                DefaultLastHttpContent(buffer)
            }
            is Map<*, *> -> {
                val encoder = HttpPostRequestEncoder(request, false)
                for((k, v) in body) {
                    val key = k.toString()
                    val attribute = when(v) {
                        is ByteBuf -> {
                            val attribute = MemoryAttribute(key)
                            attribute.setContent(v)
                            attribute
                        }
                        is String -> {
                            val buffer = v.byteBuf
                            val attribute = MemoryAttribute(key)
                            attribute.setContent(buffer)
                            attribute
                        }
                        is ByteString -> {
                            val attribute = MemoryAttribute(key)
                            val buffer = ByteBufAllocator.DEFAULT.buffer(body.size)
                            v.write(buffer)
                            attribute.setContent(buffer)
                            attribute
                        }
                        is Writable -> {
                            val buffer = ByteBufAllocator.DEFAULT.buffer()
                            v.encodeJson(JsonWriter(buffer))
                            val attribute = MemoryAttribute(key)
                            attribute.setContent(buffer)
                            attribute
                        }
                        else -> {
                            val buffer = ByteBufAllocator.DEFAULT.buffer()
                            writeJson(v, null, buffer)
                            val attribute = MemoryAttribute(key)
                            attribute.setContent(buffer)
                            attribute
                        }
                    }
                    encoder.addBodyHttpData(attribute)
                }

                context.writeAndFlush(encoder.finalizeRequest(), context.voidPromise())
                return
            }
            else -> {
                contentLength = 0
                body
            }
        }

        if(!request.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            request.headers()[HttpHeaderNames.CONTENT_LENGTH] = contentLength
        }

        if(body == null) {
            context.writeAndFlush(request, context.voidPromise())
        } else if(content is LastHttpContent) {
            context.write(request, context.voidPromise())
            context.writeAndFlush(content, context.voidPromise())
        } else {
            context.write(request, context.voidPromise())
            context.write(content, context.voidPromise())
            context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, context.voidPromise())
        }
    }

    override fun request(method: HttpMethod, path: String, listener: HttpListener) {
        onRequest(listener)
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path)
        context!!.writeAndFlush(request, context!!.voidPromise())
    }

    override fun request(method: HttpMethod, path: String, body: Any, listener: HttpListener) {
        onRequest(listener)
        val buffer = ByteBufAllocator.DEFAULT.buffer()
        writeJson(body, null, buffer)
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, buffer)
        context!!.writeAndFlush(request, context!!.voidPromise())
    }

    override fun request(method: HttpMethod, path: String, body: Array<Pair<String, Any>>, listener: HttpListener) {
        onRequest(listener)
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path)
        val encoder = HttpPostRequestEncoder(request, false)
        for((k, v) in body) {
            val attribute = when(v) {
                is File -> {
                    val attribute = DiskAttribute(k)
                    attribute.setContent(v)
                    attribute
                }
                is InputStream -> {
                    val attribute = MemoryAttribute(k)
                    attribute.setContent(v)
                    attribute
                }
                is ByteBuf -> {
                    val attribute = MemoryAttribute(k)
                    attribute.setContent(v)
                    attribute
                }
                else -> {
                    val buffer = ByteBufAllocator.DEFAULT.buffer()
                    writeJson(v, null, buffer)
                    val attribute = MemoryAttribute(k)
                    attribute.setContent(buffer)
                    attribute
                }
            }
            encoder.addBodyHttpData(attribute)
        }

        context!!.writeAndFlush(encoder.finalizeRequest(), context!!.voidPromise())
    }

    override fun close() {
        doClose()
    }

    private fun doClose() {
        val ssl = this.ssl
        if(ssl != null) {
            ssl.closeOutbound().addListener {
                context?.close()
                context = null
            }

            this.ssl = null
        } else {
            context?.close()
            context = null
        }
    }

    private fun onRequest(listener: HttpListener) {
        this.listener = listener
        requestStart = System.nanoTime()
    }

    private fun onFinish() {
        requestStart = 0
        requestEnd = System.nanoTime()

        this.listener = null
        this.result = null
    }
}