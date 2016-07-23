package com.rimmer.metrics.server.generated.api

import org.joda.time.DateTime
import io.netty.buffer.ByteBuf
import java.util.*
import com.rimmer.yttrium.*
import com.rimmer.yttrium.serialize.*
import com.rimmer.yttrium.router.plugin.IPAddress
import com.rimmer.metrics.generated.type.*

import com.rimmer.yttrium.router.*
import com.rimmer.yttrium.router.plugin.Plugin
import com.rimmer.metrics.server.generated.type.*

inline fun Router.serverApi(
    crossinline metric: RouteContext.(packets: List<MetricPacket>, ip: IPAddress) -> Task<Unit>
) {
    addRoute(
        HttpMethod.POST, 0, 
        emptyList<RouteProperty>(), 
        listOf(PathSegment("metric", null)), 
        listOf(BuilderQuery("packets", false, null, ""), BuilderQuery("ip", false, null, "")), 
        listOf(pluginMap["IPPlugin"]!!), 
        arrayOf(arrayReader<MetricPacket>(MetricPacket.reader), null), 
        null, 
        { metric(it[0] as List<MetricPacket>, it[1] as IPAddress) }
    )
}
