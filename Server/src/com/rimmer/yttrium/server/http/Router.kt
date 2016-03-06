package com.rimmer.yttrium.server.http

import com.rimmer.yttrium.InvalidStateException
import com.rimmer.yttrium.parseInt
import com.rimmer.yttrium.router.*
import com.rimmer.yttrium.router.HttpMethod
import com.rimmer.yttrium.serialize.JsonToken
import com.rimmer.yttrium.serialize.readPrimitive
import com.rimmer.yttrium.serialize.writeJson
import com.rimmer.yttrium.sliceHash
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import io.netty.util.AsciiString
import java.util.*

val jsonContentType = AsciiString("application/json")

class HttpRouter(
    val router: Router,
    val listener: RouteListener? = null,
    val defaultHandler: (FullHttpRequest, (HttpResponse) -> Unit) -> Unit = ::httpDefault
): (ChannelHandlerContext, FullHttpRequest, (HttpResponse) -> Unit) -> Unit {
    private val segmentTrees = HttpMethod.values().map { m ->
        buildSegments(router.routes.filter { it.method == m })
    }

    override fun invoke(context: ChannelHandlerContext, request: FullHttpRequest, f: (HttpResponse) -> Unit) {
        // Check if the request contains a version request.
        val versionString = request.headers().get("API-VERSION") ?: "0"
        val version = parseInt(versionString, 0)

        // Check if the requested http method is known.
        val method = convertMethod(request.method())
        if(method == null) {
            defaultHandler(request, f)
            return
        }

        // Find a matching route and parse its path parameters.
        val parameters = ArrayList<String>()
        val route = findRoute(segmentTrees[method.ordinal], parameters, version, request.uri(), 1)
        if(route == null) {
            defaultHandler(request, f)
            return
        }

        val callId = listener?.onStart(route) ?: 0
        val fail = {e: Throwable? ->
            f(mapError(e))
            listener?.onFail(callId, route, e)
        }

        try {
            val params = parseParameters(route, parameters)
            val queries = parseQuery(route, request.uri())

            // TODO: Parse request body here if needed.

            checkQueries(route, queries)
            route.handler(RouteContext(context, params, queries), object: RouteListener {
                override fun onStart(route: Route) = 0L
                override fun onSucceed(id: Long, route: Route, result: Any?) {
                    val buffer = context.alloc().buffer()
                    try {
                        writeJson(result, buffer)
                        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buffer)
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, jsonContentType)
                        f(response)
                        listener?.onSucceed(callId, route, result)
                    } catch(e: Throwable) { fail(e) }
                }
                override fun onFail(id: Long, route: Route, reason: Throwable?) { fail(reason) }
            })
        } catch(e: Throwable) { fail(e) }
    }
}

private class HttpSegment(
    val routes: Array<Route>,
    val routeHashes: IntArray,
    val next: Array<HttpSegment>,
    val nextHashes: IntArray,
    val wildcard: HttpSegment?
)

/**
 * Creates a tree of segment groups from a route list.
 * The segment endpoints within each leaf are descending-sorted by version,
 * which allows a searcher to take the first match.
 */
private fun buildSegments(routes: Iterable<Route>, segmentIndex: Int = 0): HttpSegment {
    val endPoints = routes.filter {
        it.segments.size == segmentIndex + 1
    }.sortedByDescending { it.version }.toTypedArray()

    val hashes = endPoints.map {
        val segment = it.segments[segmentIndex]
        if(segment.type == null) segment.name.hashCode() else -1
    }.toIntArray()

    val groups = routes.filter {
        it.segments.size > segmentIndex + 1 && it.segments[segmentIndex].type == null
    }.groupBy {
        it.segments[segmentIndex].name
    }

    val next = groups.map { buildSegments(it.value, segmentIndex + 1) }.toTypedArray()
    val nextHashes = groups.map { it.key.hashCode() }.toIntArray()

    val wildcardRoutes = routes.filter {
        it.segments.size > segmentIndex + 1 && it.segments[segmentIndex].type != null
    }
    val wildcards = if(wildcardRoutes.size > 0) buildSegments(wildcardRoutes, segmentIndex + 1) else null

    return HttpSegment(endPoints, hashes, next, nextHashes, wildcards)
}

/**
 * Parses an HTTP request path and returns any matching route handler.
 * @param parameters A list of parameters that will be filled
 * with the path parameters of the returned route, in reverse order.
 */
private fun findRoute(segment: HttpSegment, parameters: ArrayList<String>, version: Int, url: String, start: Int): Route? {
    // Find the first url segment. This is used to create a set of possible methods.
    val segmentStart = start
    var segmentEnd = url.indexOf('/', start + 1)
    if(segmentEnd == -1) {
        segmentEnd = url.indexOf('?', start + 1)
        if(segmentEnd == -1) {
            segmentEnd = url.length
        }
    }

    val hash = url.sliceHash(segmentStart, segmentEnd)
    if(segmentEnd >= url.length || url[segmentEnd] == '?') {
        segment.routeHashes.forEachIndexed { i, v ->
            val route = segment.routes[i]
            if((v == hash || v == -1) && route.version <= version) {
                if(v == -1) parameters.add(url.substring(segmentStart, segmentEnd))
                return route
            }
        }
        return null
    }

    val i = segment.nextHashes.indexOf(hash)
    val handler = if(i >= 0) findRoute(segment.next[i], parameters, version, url, segmentEnd + 1) else null
    if(handler != null) return handler

    val wildcard = segment.wildcard?.let { findRoute(it, parameters, version, url, segmentEnd + 1) }
    if(wildcard != null) {
        parameters.add(url.substring(segmentStart, segmentEnd))
    }
    return wildcard
}

/**
 * Parses the parameter list returned by findHandler into the correct types.
 * @param parameters A reverse list of path parameters for this route.
 */
private fun parseParameters(route: Route, parameters: Iterable<String>): Array<Any?> {
    val length = route.typedSegments.size
    val array = arrayOfNulls<Any>(length)
    parameters.forEachIndexed { i, p ->
        val index = length - i - 1
        array[index] = readPrimitive(p, route.typedSegments[index].type!!)
    }
    return array
}

/** Parses the query parameters for this route into `queries`. Returns an error string if the url is invalid. */
private fun parseQuery(route: Route, url: String): Array<Any?> {
    val params = route.queries
    val query = url.substringAfter('?', "")
    val values = arrayOfNulls<Any>(params.size)

    if(query.isNotEmpty()) {
        val queries = query.split('&')

        // Parse each query parameter.
        queries.forEach { q ->
            val separator = q.indexOf('=')
            if(separator == -1) {
                // Bad syntax.
                throw InvalidStateException("Invalid query syntax: expected '='")
            }

            // Check if this parameter is used.
            val name = q.sliceHash(0, separator)
            params.forEachIndexed { i, query ->
                if(query.hash == name) {
                    values[i] = readPrimitive(q.substring(separator + 1), query.type)
                }
            }
        }
    }

    return values
}

/** Makes sure that all required query parameters have been set correctly. */
fun checkQueries(route: Route, args: Array<Any?>) {
    route.queries.forEachIndexed { i, query ->
        val v = args[i]
        if(v == null) {
            if(query.default != null) {
                args[i] = query.default
            } else {
                val description = if(query.description.isNotEmpty()) "(${query.description})" else "(no description)"
                val type = "of type ${query.type.simpleName}"
                throw InvalidStateException(
                    "Request to ${route.name} is missing required query parameter \"${query.name}\" $description $type"
                )
            }
        }
    }
}