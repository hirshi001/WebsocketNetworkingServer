package com.hirshi001.websocketnetworkingserver;

import com.hirshi001.buffer.bufferfactory.BufferFactory;
import com.hirshi001.networking.network.channel.BaseChannel;
import com.hirshi001.networking.network.channel.DefaultChannelSet;
import com.hirshi001.networking.network.server.BaseServer;
import com.hirshi001.networking.network.server.Server;
import com.hirshi001.networking.network.server.ServerOption;
import com.hirshi001.networking.networkdata.NetworkData;
import com.hirshi001.restapi.RestAPI;
import com.hirshi001.restapi.RestFuture;
import com.hirshi001.restapi.ScheduledExec;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class WebsocketServer extends BaseServer<WebsocketServerChannel> {

    private ScheduledExec exec;
    private ConnectionServer connectionServer;

    private final Map<ServerOption, Object> options;


    /**
     * Creates a new BaseServer with the given NetworkData, BufferFactory, and port
     *
     * @param exec          the ScheduledExec to use for scheduling tasks
     * @param networkData   the NetworkData to use
     * @param bufferFactory the BufferFactory to use
     * @param port          the port to listen on
     */
    public WebsocketServer(ScheduledExec exec, NetworkData networkData, BufferFactory bufferFactory, int port) {
        super(networkData, bufferFactory, port);
        this.exec = exec;
        options = new ConcurrentHashMap<>(4);
    }

    @Override
    public RestFuture<?, Server> startTCP() {
        return RestAPI.create((future, nullInput) -> {
            connectionServer = new ConnectionServer(getPort());
            connectionServer.startTCP(() -> future.taskFinished(this));
        });
    }

    @Override
    public RestFuture<?, Server> startUDP() {
        return RestAPI.create(() -> this);
    }

    @Override
    public RestFuture<?, Server> stopTCP() {
        return RestAPI.create( ()->{
            connectionServer.stop();
            return this;
        });
    }

    @Override
    public RestFuture<?, Server> stopUDP() {
        return RestAPI.create(() -> this);
    }

    @Override
    public <T> void setServerOption(ServerOption<T> option, T value) {
        options.put(option, value);
        if(option==ServerOption.MAX_CLIENTS){
            getClients().setMaxSize((Integer) value);
        }
        else if(option==ServerOption.TCP_PACKET_CHECK_INTERVAL){
            // scheduleTCP(); nah lets not cause the thing is multithreaded
        }else if(option==ServerOption.RECEIVE_BUFFER_SIZE){
            // do nothing
        }
    }

    @Override
    public <T> T getServerOption(ServerOption<T> option) {
        return (T) options.get(option);
    }

    @Override
    public boolean supportsTCP() {
        return true;
    }

    @Override
    public boolean supportsUDP() {
        return false;
    }

    @Override
    public void close() {
        stopTCP().perform();
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public boolean tcpOpen() {
        return false;
    }

    @Override
    public boolean udpOpen() {
        return false;
    }

    protected void channelConnect(WebSocket webSocket) {
        DefaultChannelSet<WebsocketServerChannel> channelSet = (DefaultChannelSet) getClients();
        InetSocketAddress address = webSocket.getRemoteSocketAddress();
        int port = address.getPort();
        WebsocketServerChannel channel;
        synchronized (channelSet.getLock()) {
            channel = channelSet.get(address.getAddress().getAddress(), port);
            if (channel == null) {
                channel = new WebsocketServerChannel(this, exec);
                channel.connect(webSocket);
                if (!addChannel(channel)) {
                    webSocket.close(CloseFrame.NORMAL);
                }
            } else {
                channel.connect(webSocket);
            }
        }

    }


    class ConnectionServer extends WebSocketServer {

        private  Runnable onStart;

        public ConnectionServer(int port) {
            super(new InetSocketAddress(port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            channelConnect(conn);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {

        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            WebsocketServerChannel channel = conn.getAttachment();
            com.hirshi001.buffer.buffers.ByteBuffer buffer = getBufferFactory().wrap(message.array(), message.arrayOffset(), message.limit());
            channel.onTCPBytesReceived(buffer);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {

        }

        @Override
        public void onStart() {
            if(onStart!=null) onStart.run();
        }



        public void startTCP(Runnable onStart){
            this.onStart = onStart;
            this.start();
        }
    }

}
