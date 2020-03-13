package com.rimmer.yttrium.server.http

import com.rimmer.yttrium.*
import com.rimmer.yttrium.router.*
import com.rimmer.yttrium.router.HttpMethod
import com.rimmer.yttrium.router.listener.RouteListener
import com.rimmer.yttrium.serialize.BodyContent
import com.rimmer.yttrium.serialize.JsonToken
import com.rimmer.yttrium.serialize.readPrimitive
import com.rimmer.yttrium.serialize.writeJson
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.EventLoop
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.multipart.HttpData
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder
import java.net.URLDecoder
import java.util.*

class HttpRouter(
    val router: Router,
    val listener: RouteListener? = null,
    val defaultHandler: (ChannelHandlerContext, FullHttpRequest, (HttpResponse) -> Unit) -> Unit = ::httpDefault
): (ChannelHandlerContext, FullHttpRequest, (HttpResponse) -> Unit) -> Unit {
    private val segmentTrees = HttpMethod.values().map { m ->
        buildSegments(router.routes.filter { it.method == m })
    }

    override fun invoke(context: ChannelHandlerContext, request: FullHttpRequest, f: (HttpResponse) -> Unit) {
        // Check if the request contains a version request.
        val acceptVersion = request.headers().get(HttpHeaderNames.ACCEPT)?.let(::maybeParseInt)
        val version = acceptVersion ?: request.headers().get("API-VERSION")?.let(::maybeParseInt) ?: 0

        // Check if the requested http method is known.
        val method = convertMethod(request.method())
        if(method == null) {
            defaultHandler(context, request, f)
            return
        }

        // Find a matching route and parse its path parameters.
        val parameters = ArrayList<String>()
        val route = findRoute(segmentTrees[method.ordinal], parameters, version, request.uri(), 1)
        if(route == null) {
            defaultHandler(context, request, f)
            return
        }

        // The response headers that can be edited by the route handler.
        val responseHeaders = DefaultHttpHeaders(false)

        val eventLoop = context.channel().eventLoop()
        val callId = listener?.onStart(eventLoop, route) ?: 0
        val fail = {r: RouteContext, e: Throwable? ->
            f(mapError(e, route, responseHeaders))
            listener?.onFail(r, e, r.listenerData)
        }

        try {
            val queries = parseQuery(route, request.uri())
            parseParameters(route, parameters, queries)
            val parseError = parseBody(request, route, queries)

            // Make sure all required parameters were provided, and handle optional ones.
            checkArgs(route, queries, parseError)

            // Call the route with a listener that sends the result back to the client.
            val listener = object: RouteListener {
                override fun onStart(eventLoop: EventLoop, route: Route) = null
                override fun onSucceed(route: RouteContext, result: Any?, data: Any?) {
                    try {
                        val buffer = if(result is ByteBuf) {
                            result
                        } else {
                            val buffer = context.alloc().buffer()
                            writeJson(result, route.route.writer, buffer)
                            buffer
                        }

                        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buffer, responseHeaders, DefaultHttpHeaders(false))
                        if(!responseHeaders.contains(HttpHeaderNames.CONTENT_TYPE)) {
                            responseHeaders.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                        }

                        f(response)
                        listener?.onSucceed(route, result, route.listenerData)
                    } catch(e: Throwable) {
                        fail(route, e)
                    }
                }
                override fun onFail(route: RouteContext, reason: Throwable?, data: Any?) {
                    fail(route, reason)
                }
            }

            route.handler(RouteContext(context, eventLoop, route, queries, callId, false, request.headers(), responseHeaders), listener)
        } catch(e: Throwable) {
            // We don't have the call parameters here, so we just send a route context without them.
            fail(RouteContext(context, eventLoop, route, emptyArray(), callId, false, null, null), e)
        }
    }
}

private class HttpSegment(
    val localRoutes: Array<Route>,
    val localHashes: IntArray,
    val localWildcards: Array<Route>,
    val nextRoutes: Array<HttpSegment>,
    val nextHashes: IntArray,
    val nextWildcards: HttpSegment?
)

/**
 * Creates a tree of segment groups from a route list.
 * The segment endpoints within each leaf are descending-sorted by version,
 * which allows a searcher to take the first match.
 */
private fun buildSegments(routes: Iterable<Route>, segmentIndex: Int = 0): HttpSegment {
    val (endPoints, wildcardEndpoints) = routes.filter {
        it.segments.size == segmentIndex + 1
    }.sortedByDescending(
        Route::version
    ).partition {
        it.segments[segmentIndex].arg === null
    }.run {
        first.toTypedArray() to second.toTypedArray()
    }

    val hashes = endPoints.map {
        val segment = it.segments[segmentIndex]
        segment.name.hashCode()
    }.toIntArray()

    val groups = routes.filter {
        it.segments.size > segmentIndex + 1 && it.segments[segmentIndex].arg === null
    }.groupBy {
        it.segments[segmentIndex].name
    }

    val next = groups.map { buildSegments(it.value, segmentIndex + 1) }.toTypedArray()
    val nextHashes = groups.map { it.key.hashCode() }.toIntArray()

    val wildcardRoutes = routes.filter {
        it.segments.size > segmentIndex + 1 && it.segments[segmentIndex].arg !== null
    }
    val wildcards = if(wildcardRoutes.isNotEmpty()) buildSegments(wildcardRoutes, segmentIndex + 1) else null

    return HttpSegment(endPoints, hashes, wildcardEndpoints, next, nextHashes, wildcards)
}

/**
 * Parses an HTTP request path and returns any matching route handler.
 * @param parameters A list of parameters that will be filled
 * with the path parameters of the returned route, in reverse order.
 */
private fun findRoute(segment: HttpSegment, parameters: ArrayList<String>, version: Int, url: String, start: Int): Route? {
    // Filter out any leading slashes.
    var segmentStart = start
    while(url.getOrNull(segmentStart) == '/') segmentStart++

    // Find the first url segment. This is used to create a set of possible methods.
    var segmentEnd = url.indexOf('/', segmentStart + 1)
    if(segmentEnd == -1) {
        segmentEnd = url.indexOf('?', segmentStart + 1)
        if(segmentEnd == -1) {
            segmentEnd = url.length
        }
    }

    val hash = url.sliceHash(segmentStart, segmentEnd)
    if(segmentEnd >= url.length || url[segmentEnd] == '?') {
        segment.localHashes.forEachIndexed { i, v ->
            val route = segment.localRoutes[i]
            if((v == hash) && route.version <= version) return route
        }

        segment.localWildcards.forEach {
            if(it.version <= version) {
                parameters.add(url.substring(segmentStart, segmentEnd))
                return it
            }
        }
        return null
    }

    val i = segment.nextHashes.indexOf(hash)
    val handler = if(i >= 0) findRoute(segment.nextRoutes[i], parameters, version, url, segmentEnd + 1) else null
    if(handler != null) return handler

    val wildcard = segment.nextWildcards?.let { findRoute(it, parameters, version, url, segmentEnd + 1) }
    if(wildcard != null) {
        parameters.add(url.substring(segmentStart, segmentEnd))
    }
    return wildcard
}

private fun argApplicable(name: Int, arg: Arg) =
    arg.name.hashCode() == name &&
    arg.visibility !== ArgVisibility.Internal &&
    arg.isPath == false &&
    arg.type !== BodyContent::class.java

/**
 * Parses the parameter list returned by findHandler into the correct types.
 * @param parameters A reverse list of path parameters for this route.
 */
private fun parseParameters(route: Route, parameters: Iterable<String>, args: Array<Any?>) {
    val length = route.typedSegments.size
    parameters.forEachIndexed { i, p ->
        val segment = route.typedSegments[length - i - 1]
        val string = URLDecoder.decode(p, "UTF-8")
        args[segment.argIndex] = readPrimitive(string, segment.arg!!.reader!!.target)
    }
}

/** Parses the query parameters for this route into `queries`. Returns an error string if the url is invalid. */
private fun parseQuery(route: Route, url: String): Array<Any?> {
    val params = route.args
    val query = url.substringAfter('?', "")
    val values = arrayOfNulls<Any>(params.size)

    if(query.isNotEmpty()) {
        val queries = query.split('&')

        // Parse each query parameter.
        for(q in queries) {
            // Filter out any empty query parameters.
            if(q.isEmpty()) continue

            val separator = q.indexOf('=')
            if(separator == -1) {
                // Bad syntax.
                throw InvalidStateException("Invalid query syntax: expected '='")
            }

            // Check if this parameter is used.
            val name = URLDecoder.decode(q.substring(0, separator), "UTF-8").hashCode()
            params.forEachIndexed { i, query ->
                if(argApplicable(name, query)) {
                    val string = URLDecoder.decode(q.substring(separator + 1), "UTF-8")
                    if(string.isNotEmpty()) {
                        try {
                            values[i] = readPrimitive(string, query.type)
                        } catch(e: Throwable) {
                            val reader = query.reader

                            // Also try to parse as url-encoded json.
                            if(reader !== null) {
                                val json = JsonToken(string.byteBuf)
                                values[i] = reader.fromJson(json)
                            } else throw e
                        }
                    }
                }
            }
        }
    }

    return values
}

/** Parses the content of the request body, depending on how the route is configured. */
fun parseBody(request: FullHttpRequest, route: Route, queries: Array<Any?>): Throwable? {
    // Parse any parameters that were provided through the request body.
    // We parse as json if that content type is set, otherwise as form data.
    // If there is a body handler, we whole body is sent there.
    val content = request.content()
    val initialIndex = content.readerIndex()
    val bodyHandler = route.bodyQuery

    return if(bodyHandler == null) {
        if(request.headers()[HttpHeaderNames.CONTENT_TYPE]?.startsWith("application/json") == true) {
            parseJsonBody(route, request, queries)
        } else {
            parseBodyQuery(route, request, queries)
        }
    } else {
        queries[bodyHandler] = BodyContent(content.readerIndex(initialIndex))
        null
    }
}

/**
 * Parses any query parameters that were provided through the request body.
 * @return The first parsing error that occurred, which can be propagated if the whole request fails due it.
 */
fun parseBodyQuery(route: Route, request: FullHttpRequest, queries: Array<Any?>): Throwable? {
    var error: Throwable? = null
    if(request.content().readableBytes() > 0) {
        val bodyDecoder = HttpPostRequestDecoder(request)

        try {
            while(try { bodyDecoder.hasNext() } catch(e: HttpPostRequestDecoder.EndOfDataDecoderException) { false }) {
                val p = bodyDecoder.next() as? HttpData ?: continue

                // Check if this parameter is recognized.
                val name = p.name.hashCode()
                route.args.forEachIndexed { i, query ->
                    if(argApplicable(name, query)) {
                        val buffer = p.byteBuf
                        val index = buffer.readerIndex()

                        // This is a bit ugly - we don't really know if the provided data is json or raw,
                        // so we just try parsing it in both ways.
                        try {
                            queries[i] = query.reader!!.fromJson(JsonToken(buffer))

                            // If the parser didn't read the whole buffer, there was probably a conflict.
                            if(buffer.readableBytes() > 0) throw Exception()
                        } catch(e: Throwable) {
                            buffer.readerIndex(index)
                            try {
                                queries[i] = readPrimitive(buffer.string, query.type)
                            } catch(e: Throwable) {
                                // If both parsing tries failed, we set the exception to be propagated if needed.
                                if(error == null) {
                                    error = e
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            bodyDecoder.destroy()
        }
    }
    return error
}

/** Parses query parameters from a json body. */
fun parseJsonBody(route: Route, request: FullHttpRequest, queries: Array<Any?>): Throwable? {
    val buffer = request.content()
    if(buffer.isReadable) {
        try {
            val json = JsonToken(buffer)
            json.expect(JsonToken.Type.StartObject)
            while(true) {
                json.parse()
                if(json.type == JsonToken.Type.EndObject) {
                    break
                } else if(json.type == JsonToken.Type.FieldName) {
                    val name = json.stringPayload.hashCode()
                    var found = false
                    route.args.forEachIndexed { i, query ->
                        if(argApplicable(name, query)) {
                            found = true
                            val offset = buffer.readerIndex()

                            if(json.peekString()) {
                                // Sometimes body parameters are inside a json string, which we need to support.
                                // Since we can't know which one it is, we have to try both...
                                try {
                                    if(!json.skipNull()) queries[i] = query.reader!!.fromJson(json)
                                } catch(e: Throwable) {
                                    buffer.readerIndex(offset)
                                    json.parse()
                                    val subJson = JsonToken(json.stringPayload.byteBuf)
                                    if(!subJson.skipNull()) queries[i] = query.reader!!.fromJson(subJson)
                                }
                            } else {
                                if(!json.skipNull()) queries[i] = query.reader!!.fromJson(json)
                            }
                        }
                    }

                    if(!found) {
                        json.skipValue()
                    }
                } else {
                    return InvalidStateException("Expected json field name before offset ${request.content().readerIndex()}")
                }
            }
        } catch(e: Throwable) {
            return e
        }
    }

    return null
}

/** Makes sure that all required query parameters have been set correctly. */
fun checkArgs(route: Route, args: Array<Any?>, parseError: Throwable?) {
    route.args.forEachIndexed { i, query ->
        val v = args[i]
        if(v == null && query.visibility.exported) {
            if(query.optional) {
                args[i] = query.default
            } else {
                val type = "of type ${query.type.simpleName}"
                val error = if(parseError != null) "due to $parseError" else ""
                throw InvalidStateException(
                    "Request to ${route.name} is missing required query parameter \"${query.name}\" $type $error"
                )
            }
        }
    }
}