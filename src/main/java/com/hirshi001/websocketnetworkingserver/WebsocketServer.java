package com.hirshi001.websocketnetworkingserver;

import com.hirshi001.buffer.bufferfactory.BufferFactory;
import com.hirshi001.networking.network.channel.Channel;
import com.hirshi001.networking.network.channel.ChannelSet;
import com.hirshi001.networking.network.channel.DefaultChannelSet;
import com.hirshi001.networking.network.server.BaseServer;
import com.hirshi001.networking.network.server.Server;
import com.hirshi001.networking.network.server.ServerOption;
import com.hirshi001.networking.networkdata.NetworkData;
import com.hirshi001.restapi.RestAPI;
import com.hirshi001.restapi.RestFuture;
import com.hirshi001.restapi.ScheduledExec;
import com.hirshi001.restapi.TimerAction;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketServerFactory;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


public class WebsocketServer extends BaseServer<WebsocketServerChannel> {

    private ConnectionServer connectionServer;

    private WebSocketServerFactory webSocketServerFactory;

    protected DefaultChannelSet<WebsocketServerChannel> channelSet;
    private final Object tcpLock = new Object();
    TimerAction tcpDataCheck;
    volatile boolean autoHandlePackets = true;


    /**
     * Creates a new BaseServer with the given NetworkData, BufferFactory, and port
     *
     * @param exec          the ScheduledExec to use for scheduling tasks
     * @param networkData   the NetworkData to use
     * @param bufferFactory the BufferFactory to use
     * @param port          the port to listen on
     */
    public WebsocketServer(ScheduledExec exec, NetworkData networkData, BufferFactory bufferFactory, int port) {
        super(exec, networkData, bufferFactory, port);
        channelSet = new DefaultChannelSet<>(this, ConcurrentHashMap.newKeySet());
    }

    public void setWebsocketSocketServerFactory(WebSocketServerFactory webSocketServerFactory) {
        this.webSocketServerFactory = webSocketServerFactory;
    }

    public WebSocketServer getWebSocketServer() {
        return connectionServer;
    }

    @Override
    public RestFuture<?, Server> startTCP() {
        return RestAPI.create((future, nullInput) -> {
            connectionServer = new ConnectionServer(getPort());
            if (webSocketServerFactory != null) connectionServer.setWebSocketFactory(webSocketServerFactory);
            connectionServer.startTCP(() -> {
                scheduleTCP();
                future.taskFinished(this);
            });
        });
    }

    private void scheduleTCP() {
        synchronized (tcpLock) {
            if (tcpDataCheck != null) {
                tcpDataCheck.cancel();
                tcpDataCheck = null;

            }

            Integer delay = getServerOption(ServerOption.TCP_PACKET_CHECK_INTERVAL);
            if (delay == null) delay = 0;
            if(delay<0) {
                autoHandlePackets = false;
            }
            else if(delay==0) {
                autoHandlePackets = true;
            }
            else {
                tcpDataCheck = getExecutor().repeat(this::checkTCPPackets,  0, delay, TimeUnit.MILLISECONDS);
                autoHandlePackets = false;
            }
        }

    }

    @Override
    public void checkTCPPackets() {
        for(WebsocketServerChannel channel : channelSet) {
            if(channel.tcpReceiveBuffer.readableBytes()>0) {
                channel.onTCPBytesReceived(channel.tcpReceiveBuffer);
            }
        }
    }

    @Override
    public RestFuture<?, Server> startUDP() {
        return RestAPI.create(() -> this);
    }

    @Override
    public RestFuture<?, Server> stopTCP() {
        return RestAPI.create(() -> {
            connectionServer.stop();
            connectionServer.isOpen = false;
            return this;
        });
    }

    @Override
    public RestFuture<?, Server> stopUDP() {
        return RestAPI.create(() -> this);
    }

    @Override
    protected <T> void activateServerOption(ServerOption<T> option, T value) {
        super.activateServerOption(option, value);
        if (option == ServerOption.MAX_CLIENTS) {
            getClients().setMaxSize((Integer) value);
        } else if (option == ServerOption.TCP_PACKET_CHECK_INTERVAL) {
            scheduleTCP();
        } else if (option == ServerOption.RECEIVE_BUFFER_SIZE) {
            // do nothing since websockets is tcp, no udp supported
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChannelSet<Channel> getClients() {
        return (ChannelSet<Channel>) (Object) channelSet;
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
    public RestFuture<?, ? extends WebsocketServer> close() {
        return RestAPI.create(() -> {
            try {
                stopTCP().perform().get();
            } catch (InterruptedException | ExecutionException ignored) {
            }
            return this;
        });
    }

    @Override
    public boolean tcpOpen() {
        return connectionServer.isOpen;
    }


    @Override
    public boolean udpOpen() {
        return false;
    }

    @SuppressWarnings("unchecked")
    protected void channelConnect(WebSocket webSocket) {
        DefaultChannelSet<WebsocketServerChannel> channelSet = (DefaultChannelSet) getClients();
        InetSocketAddress address = webSocket.getRemoteSocketAddress();
        int port = address.getPort();
        WebsocketServerChannel channel;

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


    class ConnectionServer extends WebSocketServer {

        private Runnable onStart;
        private boolean isOpen = false;

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
            channel.tcpReceiveBuffer.writeBytes(getBufferFactory().wrap(message.array(), message.arrayOffset(), message.limit()));
            if(autoHandlePackets) {
                channel.onTCPBytesReceived(channel.tcpReceiveBuffer);
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {

        }



        @Override
        public void onStart() {
            if (onStart != null) onStart.run();
            isOpen = true;
        }


        public void startTCP(Runnable onStart) {
            this.onStart = onStart;
            this.start();
        }
    }

}
