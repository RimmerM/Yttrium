package com.rimmer.yttrium.server.binary

import com.rimmer.yttrium.server.ServerContext
import com.rimmer.yttrium.server.listen
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline

fun listenBinary(
    context: ServerContext,
    port: Int,
    useNative: Boolean = false,
    allowReuse: Boolean = false,
    pipeline: (ChannelPipeline.() -> Unit)? = null,
    handler: (ChannelHandlerContext, source: ByteBuf, target: ByteBuf, push: () -> Unit) -> Unit
) = listen(context, port, useNative, allowReuse) {
    addLast(BinaryHandler(handler))
    pipeline?.invoke(this)
}

class BinaryHandler(
    val f: (ChannelHandlerContext, source: ByteBuf, target: ByteBuf, push: () -> Unit) -> Unit
): BinaryDecoder() {
    override fun handlePacket(context: ChannelHandlerContext, request: Int, packet: ByteBuf) {
        writePacket(context, request) { target, commit ->
            f(context, packet, target, commit)
        }
    }
}
