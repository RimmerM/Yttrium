package com.rimmer.yttrium.server.http

import com.rimmer.yttrium.Context
import com.rimmer.yttrium.Task
import com.rimmer.yttrium.server.ServerContext
import io.netty.buffer.ByteBuf
import io.netty.channel.EventLoop
import io.netty.channel.EventLoopGroup
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import java.io.IOException
import java.util.*

/**
 * @param maxItems The maximum number of active connections in the pool.
 * @param maxIdleTime The maximum idle time in ns for a connection. After this time it is destroyed.
 * @param maxBusyTime The maximum busy time in ns for a connection. After it has been busy for this time, it is destroyed.
 * @param maxWaiting The maximum number of users that can be waiting for a connection.
 * @param checkDelta The minimum time between checks for stuck connections.
 * @param debug If set, the pool prints debug text when connections are created and closed.
 */
class PoolConfiguration(
    val maxItems: Int,
    val maxIdleTime: Long = 60L * 1000L * 1000000L,
    val maxBusyTime: Long = 60L * 1000L * 1000000L,
    val maxWaiting: Int = 128,
    val checkDelta: Long = 60L * 1000L * 1000000L,
    val debug: Boolean = false
)

/** Provides an interface for using http clients from a pool. */
interface ClientPool {
    /** Returns a pooled connection ready for use. */
    fun get(f: (HttpClient?, Throwable?) -> Unit)

    /** Releases a pooled connection. This is also done by HttpClient.close() */
    fun release(client: HttpClient)
}

/** Implements a connection pool meant for use from a single thread. */
class SingleThreadPool(
    val config: PoolConfiguration,
    val creator: ((HttpClient?, Throwable?) -> Unit) -> Unit
): ClientPool {
    private val idlePool = Stack<PooledClient>()
    private val connections = ArrayList<PooledClient>()
    private val waiting: Queue<(HttpClient?, Throwable?) -> Unit> = LinkedList()
    private var connectionCount = 0
    private var lastCheck = System.nanoTime()

    override fun get(f: (HttpClient?, Throwable?) -> Unit) {
        if(idlePool.empty()) {
            if(connectionCount < config.maxItems) {
                if(config.debug) {
                    println("Creating new Http client in pool.")
                }

                connectionCount++
                creator {c, e ->
                    if(c == null) {
                        f(null, e)
                    } else {
                        val pooled = PooledClient(c, this)
                        connections.add(pooled)
                        f(pooled, null)
                    }
                }
            } else {
                // There being no connections available may indicate a high load,
                // but may also indicate connections being stuck.
                // We check each one for request timeouts.
                val time = System.nanoTime()
                if(time - lastCheck > config.checkDelta) {
                    checkConnections()
                    lastCheck = time
                    get(f)
                } else if(waiting.size < config.maxWaiting) {
                    // Queue this request if we have space left.
                    waiting.offer(f)
                } else {
                    f(null, IOException("Connection queue full"))
                }
            }
        } else {
            val connection = idlePool.pop()

            // If the connection timed out or is otherwise unusable, we drop it and create a new one.
            if(connection.busy || !connection.connected || connection.idleTime > config.maxIdleTime) {
                if(config.debug) {
                    println("Closing timed out Http connection.")
                }

                connection.connection.close()
                connections.remove(connection)
                connectionCount--
                checkConnections()

                get(f)
            } else {
                f(connection, null)
            }
        }
    }

    override fun release(client: HttpClient) {
        if(client is PooledClient) {
            val waiter = waiting.poll()
            if(client.busy || !client.connected || client.idleTime > config.maxIdleTime) {
                client.connection.close()
                connections.remove(client)
                connectionCount--
                if(waiter != null) get(waiter)
            } else {
                // If anyone is currently waiting for a connection, we directly call it.
                // Otherwise we return the connection to the pool.
                if(waiter == null) {
                    idlePool.add(client)
                } else {
                    waiter(client, null)
                }
            }
        }
    }

    fun checkConnections() {
        if(config.debug) {
            println("Checking Http clients...")
        }

        var i = 0
        while(i < connections.size) {
            val c = connections[i]
            val busy = config.maxBusyTime > 0 && c.busyTime > config.maxBusyTime
            val idle = config.maxIdleTime > 0 && c.idleTime > config.maxIdleTime
            if(busy || idle) {
                connectionCount--
                c.connection.close()
                connections.removeAt(i)

                if(config.debug) {
                    println("Closing idle Http client $i")
                }
            } else {
                i++
            }
        }
    }
}

/** Wraps a connection managed by a pool. */
class PooledClient(val connection: HttpClient, val pool: ClientPool): HttpClient by connection {
    override fun close() {
        pool.release(this)
    }
}

/** A connection pool that manages one single-thread connection pool for each event loop in a group. */
class HttpPool(
    context: ServerContext,
    val config: PoolConfiguration,
    val creator: (EventLoopGroup, (HttpClient?, Throwable?) -> Unit) -> Unit
) {
    private val pool: Map<EventLoop, SingleThreadPool>

    init {
        // We initialize the pool at creation to avoid synchronization when lazily creating pools.
        val map = HashMap<EventLoop, SingleThreadPool>()
        context.handlerGroup.forEach { loop ->
            if(loop is EventLoop) {
                map[loop] = SingleThreadPool(config) { creator(loop, it) }
            }
        }

        pool = map
    }

    operator fun get(context: Context): SingleThreadPool {
        return pool[context.eventLoop] ?:
            throw IllegalArgumentException("Invalid context event loop - the event loop must be in the server's handlerGroup.")
    }
}

fun HttpPool.request(context: Context, request: HttpRequest, listener: HttpListener? = null): Task<HttpResult> {
    val task = Task<HttpResult>()

    this[context].get { c, e ->
        if(e == null) {
            c!!.request(request, null, wrappedListener(task, listener, c))
        } else {
            task.fail(e)
        }
    }
    return task
}

fun HttpPool.request(context: Context, method: HttpMethod, path: String, listener: HttpListener? = null): Task<HttpResult> {
    val task = Task<HttpResult>()
    this[context].get { c, e ->
        if(e == null) {
            c!!.request(method, path, wrappedListener(task, listener, c))
        } else {
            task.fail(e)
        }
    }
    return task
}

fun HttpPool.request(context: Context, method: HttpMethod, path: String, body: Any, listener: HttpListener? = null): Task<HttpResult> {
    val task = Task<HttpResult>()
    this[context].get { c, e ->
        if(e == null) {
            c!!.request(method, path, body, wrappedListener(task, listener, c))
        } else {
            task.fail(e)
        }
    }
    return task
}

fun HttpPool.request(context: Context, method: HttpMethod, path: String, body: Array<Pair<String, Any>>, listener: HttpListener? = null): Task<HttpResult> {
    val task = Task<HttpResult>()
    this[context].get { c, e ->
        if(e == null) {
            c!!.request(method, path, body, wrappedListener(task, listener, c))
        } else {
            task.fail(e)
        }
    }
    return task
}

internal fun wrappedListener(task: Task<HttpResult>, listener: HttpListener?, connection: HttpClient): HttpListener {
    return if(listener === null) {
        object : HttpListener {
            override fun onContent(result: HttpResult, content: ByteBuf, finished: Boolean): Boolean {
                if(finished) {
                    connection.close()
                    task.finish(result)
                }

                return true
            }
            override fun onError(error: Throwable) { task.fail(error) }
        }
    } else {
        object : HttpListener {
            override fun onResult(result: HttpResult): Boolean {
                return listener.onResult(result)
            }

            override fun onContent(result: HttpResult, content: ByteBuf, finished: Boolean): Boolean {
                val ret = listener.onContent(result, content, finished)
                if(finished || !ret) {
                    connection.close()
                    task.finish(result)
                }

                return ret
            }

            override fun onError(error: Throwable) {
                listener.onError(error)
                task.fail(error)
            }
        }
    }
}