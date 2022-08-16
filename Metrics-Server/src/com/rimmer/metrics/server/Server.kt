package com.rimmer.metrics.server

import com.rimmer.metrics.generated.type.ErrorPacket
import com.rimmer.metrics.generated.type.ProfilePacket
import com.rimmer.metrics.generated.type.StatPacket
import com.rimmer.metrics.server.generated.api.clientApi
import com.rimmer.metrics.server.generated.api.serverApi
import com.rimmer.yttrium.*
import com.rimmer.yttrium.router.RouteContext
import com.rimmer.yttrium.router.RouteModifier
import com.rimmer.yttrium.router.RouteProperty
import com.rimmer.yttrium.router.Router
import com.rimmer.yttrium.router.listener.RouteListener
import com.rimmer.yttrium.router.plugin.AddressPlugin
import com.rimmer.yttrium.router.plugin.Plugin
import com.rimmer.yttrium.serialize.stringReader
import com.rimmer.yttrium.server.ServerContext
import com.rimmer.yttrium.server.binary.BinaryRouter
import com.rimmer.yttrium.server.binary.listenBinary
import com.rimmer.yttrium.server.http.HttpRouter
import com.rimmer.yttrium.server.http.listenHttp

/** Runs a server that receives metrics and stores them in-memory. */
fun storeServer(context: ServerContext, store: MetricStore, port: Int, useNative: Boolean = false, allowReuse: Boolean = false) {
    val router = Router(listOf(AddressPlugin()) as List<Plugin<in Any>>)
    router.serverApi(
        metric = { it, name, ip ->
            it.forEach {
                when(it) {
                    is StatPacket -> store.onStat(it, name, ip.ip)
                    is ProfilePacket -> store.onProfile(it, name, ip.ip)
                    is ErrorPacket -> store.onError(it, name, ip.ip)
                }
            }
            finished(Unit)
        }
    )

    listenBinary(context, port, useNative, allowReuse, null, BinaryRouter(router, ErrorListener()))
}

/** Runs a server that listens for client requests and sends metrics data. */
fun clientServer(context: ServerContext, store: MetricStore, port: Int, httpPort: Int, password: String, useNative: Boolean = false, allowReuse: Boolean = false) {
    val router = Router(listOf(PasswordPlugin(password), AddressPlugin()) as List<Plugin<in Any>>)

    router.clientApi(
        getStats = {from: Long, to: Long ->
            finished(store.getStats(from, to))
        },
        getProfile = {from: Long, to: Long ->
            finished(store.getProfiles(from, to))
        },
        getError = {from: Long ->
            finished(store.getErrors(from))
        }
    )

    val errorListener = ErrorListener()
    listenBinary(context, port, useNative, allowReuse, null, BinaryRouter(router, errorListener))
    listenHttp(context, httpPort, true, useNative, handler = HttpRouter(router, errorListener))
}

class PasswordPlugin(val password: String): Plugin<Int> {
    override val name = "PasswordPlugin"

    override fun modifyRoute(modifier: RouteModifier, properties: List<RouteProperty>): Int {
        return modifier.addArg("password", String::class.java, stringReader)
    }

    override fun modifyCall(context: Int, route: RouteContext, f: (Throwable?) -> Unit) {
        if((route.parameters[context] as String) != password) f(UnauthorizedException())
        else f(null)
    }
}

class ErrorListener: RouteListener {
    override fun onFail(route: RouteContext, reason: Throwable?, data: Any?) {
        println("Route ${route.route.name} failed with $reason")
        if(!(reason is InvalidStateException ||
            reason is UnauthorizedException ||
            reason is NotFoundException ||
            (reason is HttpException && reason.errorCode == 429)
        )) reason?.printStackTrace()
    }
}