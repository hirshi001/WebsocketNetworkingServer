package com.hirshi001.websocketnetworkingserver;

import com.hirshi001.buffer.buffers.ByteBuffer;
import com.hirshi001.networking.network.channel.BaseChannel;
import com.hirshi001.networking.network.channel.Channel;
import com.hirshi001.networking.network.networkside.NetworkSide;
import com.hirshi001.restapi.RestAPI;
import com.hirshi001.restapi.RestFuture;
import com.hirshi001.restapi.ScheduledExec;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;

public class WebsocketServerChannel extends BaseChannel {

    WebSocket webSocket;
    public ByteBuffer tcpReceiveBuffer;


    public WebsocketServerChannel(NetworkSide networkSide, ScheduledExec executor) {
        super(networkSide, executor);
        tcpReceiveBuffer = getSide().getBufferFactory().buffer(1024);
    }

    public void connect(WebSocket webSocket) {
        this.webSocket = webSocket;
        webSocket.setAttachment(this);
        onTCPConnected();
    }

    @Override
    public void onTCPBytesReceived(ByteBuffer bytes) {
        super.onTCPBytesReceived(bytes);
    }

    @Override
    protected void writeAndFlushTCP(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        webSocket.send(bytes);
    }

    @Override
    protected void writeAndFlushUDP(ByteBuffer buffer) {
        // do nothing
    }

    @Override
    public String getIp() {
        return webSocket.getRemoteSocketAddress().getHostString();
    }

    @Override
    public int getPort() {
        return webSocket.getLocalSocketAddress().getPort();
    }

    @Override
    public byte[] getAddress() {
        return webSocket.getRemoteSocketAddress().getAddress().getAddress();
    }

    @Override
    public RestFuture<?, Channel> startTCP() {
        return RestAPI.create(() -> {
            throw new UnsupportedOperationException("Cannot start TCP on a websocket server channel");
        });
    }

    @Override
    public RestFuture<?, Channel> stopTCP() {
        return RestAPI.create(() -> {
            webSocket.close(CloseFrame.NORMAL);
            onTCPDisconnected();
            return this;
        });
    }

    @Override
    public RestFuture<?, Channel> startUDP() {
        return RestAPI.create(()->this);
    }

    @Override
    public RestFuture<?, Channel> stopUDP() {
        return RestAPI.create(()->this);
    }

    @Override
    public boolean isTCPOpen() {
        return webSocket != null && webSocket.isOpen();
    }

    @Override
    public boolean isUDPOpen() {
        return false;
    }

    @Override
    public void checkTCPPackets() {
        if(tcpReceiveBuffer.readableBytes()>0) {
            onTCPBytesReceived(tcpReceiveBuffer);
        }
        super.checkTCPPackets();
    }
}
