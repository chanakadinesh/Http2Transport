package org.wso2.carbon.http2.transport.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.http.protocol.HTTP;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Created by chanakabalasooriya on 9/13/16.
 */
public class Http2ConnectionFactory {

    private static Http2ConnectionFactory factory;
    private static volatile TreeMap<String ,Http2ClientHandler> connections;
    private Log log=LogFactory.getLog(Http2ConnectionFactory.class);

    private Http2ConnectionFactory(){
        connections=new TreeMap<>();
    }
    public static Http2ConnectionFactory getInstance(TransportOutDescription trasportOut){
        if(factory==null){
            factory=new Http2ConnectionFactory();
        }
        return factory;
    }

    public Http2ClientHandler getChannelHandler(URI uri) {
        Http2ClientHandler handler;
        log.debug("Request new connection");
        handler=getClientHandlerFromPool(uri);
        if(handler==null){
            handler=cacheNewConnection(uri);
        }
        return handler;
    }

    public Http2ClientHandler cacheNewConnection(URI uri){
        log.debug("Caching new connection");
        final SslContext sslCtx;
        final boolean SSL;
        if(uri.getScheme().equalsIgnoreCase(Http2Constants.HTTPS2) || uri.getScheme().equalsIgnoreCase("https")){
            SSL=true;
        }else
            SSL=false;
        try {
            if (SSL) {
                SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
                sslCtx = SslContextBuilder.forClient()
                        .sslProvider(provider)
                /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                 * Please refer to the HTTP/2 specification for cipher requirements. */
                        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocolConfig(new ApplicationProtocolConfig(
                                ApplicationProtocolConfig.Protocol.ALPN,
                                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                ApplicationProtocolNames.HTTP_2,
                                ApplicationProtocolNames.HTTP_1_1))
                        .build();
            } else {
                sslCtx = null;
            }

            EventLoopGroup workerGroup = new NioEventLoopGroup();
            Http2ClientInitializer initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE);

            String HOST = uri.getHost();
            Integer PORT = uri.getPort();
            // Configure the client.
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.remoteAddress(HOST, PORT);
            b.handler(initializer);
            // Start the client.
            Channel channel = b.connect().syncUninterruptibly().channel();
            log.debug("Connected to [" + HOST + ':' + PORT + ']');
            // Wait for the HTTP/2 upgrade to occur.
            Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
            http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);

            String key = generateKey(uri);
            Http2ClientHandler handler = initializer.responseHandler();
            handler.setChannel(channel);
            connections.put(key, handler);
            return initializer.responseHandler();
        }catch (SSLException e){
            log.error("Error while connection establishment:"+e.fillInStackTrace());
            return null;
        }
        catch (Exception e){
            log.error("Error while connection establishment:"+e.fillInStackTrace());
            return null;
        }
    }

    public Http2ClientHandler getClientHandlerFromPool(URI uri){
        String key=generateKey(uri);
        Http2ClientHandler handler= connections.get(key);
        if(handler!=null){
            Channel c=handler.getChannel();
            if(!c.isActive()){
                connections.remove(key);
                handler= cacheNewConnection(uri);
            }
        }
        return handler;
    }

    public String generateKey(URI uri){
        String host=uri.getHost();
        int port=uri.getPort();
        String ssl;
        if(uri.getScheme().equalsIgnoreCase(Http2Constants.HTTPS2) || uri.getScheme().equalsIgnoreCase("https")){
            ssl="https://";
        }else
            ssl="http://";
        return ssl+host+":"+port;
    }
}
