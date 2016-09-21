package org.wso2.carbon.http2.transport.util;


import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import org.apache.synapse.MessageContext;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * An HTTP2 client that allows you to send HTTP2 frames to a server. Inbound and outbound frames are
 * logged. When run from the command-line, sends a single HEADERS frame to the server and gets back
 * a "Hello World" response.
 */
public final class Http2Client {

   /* static final String data="<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:m=\"http://services.samples\">\n" +
            "<soapenv:Header/>\n" +
            "  <soapenv:Body>\n" +
            "    <m:getQuote xmlns:m=\"http://services.samples\">\n" +
            "      <m:CheckPriceRequest>\n" +
            "        <m:Code>WSO2</m:Code>\n" +
            "      </m:CheckPriceRequest>\n" +
            "    </m:getQuote>\n" +
            "  </soapenv:Body>\n" +
            "</soapenv:Envelope>";
*/
    static final boolean SSL = System.getProperty("ssl") != null;
    static final String HOST = System.getProperty("host", "wso2123-ThinkPad-T530");
    static final int PORT = Integer.parseInt(System.getProperty("port", SSL? "8889" : "8888"));
    static final String URL = System.getProperty("url", "stockquote/view/IBM");
    static final String URL2 = System.getProperty("url2","services/SimpleStockQuoteProxy.SimpleStockQuoteProxyHttpSoap11Endpoint");
   // static final String URL2DATA = System.getProperty("url2data", data);
//    HttpMethod method=GET;


    private boolean ssl;
    private String host;
    private int port;
    private String url;
    private HttpMethod method;
    private String data;

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setData(String data) {
        this.data = data;
    }

    public void send(Channel channel,String method,String url){
        send(channel,method,url,null);
    }

    public void send(Channel channel,String method,String url,String data){



    }

    public Channel getChannel(String host,int port){
        return Http2ConnectionPool.getConnection(host,port);
    }

    public void send(MessageContext msgCtx){
        final SslContext sslCtx;
       /* if (SSL) {
            SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
            sslCtx = SslContextBuilder.forClient()
                    .sslProvider(provider)
                *//* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                 * Please refer to the HTTP/2 specification for cipher requirements. *//*
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                    .build();
        } else {*/
            sslCtx = null;
        //}

        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Http2ClientInitializer initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE);

        try {
            // Configure the client.
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.remoteAddress(HOST, PORT);
            b.handler(initializer);
            // Start the client.
            Channel channel = b.connect().syncUninterruptibly().channel();
            System.out.println("Connected to [" + HOST + ':' + PORT + ']');
            // Wait for the HTTP/2 upgrade to occur.
            Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
            http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);

            Http2ClientHandler responseHandler = initializer.responseHandler();
            int streamId = 3;
            HttpScheme scheme = SSL ? HttpScheme.HTTPS : HttpScheme.HTTP;
            AsciiString hostName = new AsciiString(HOST + ':' + PORT);
            System.err.println("Sending request(s)...");




            if(GET.equals(method)) {
                // Create a simple GET request.
                FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, URL);
                request.headers().add(HttpHeaderNames.HOST, hostName);
                request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
                responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
                streamId += 2;
            }else if(POST.equals(method)) {
                // Create a simple POST request with a body.
                FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, POST, URL,
                        Unpooled.copiedBuffer(data.getBytes(CharsetUtil.UTF_8)));
                request.headers().add(HttpHeaderNames.HOST, hostName);
                request.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/xml");
                request.headers().add("SOAPAction", "urn:getQuote");
                request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
                responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
            }else {
            //  streamId += 2;
            }
            responseHandler.awaitResponses(3, TimeUnit.SECONDS);
            System.out.println("Finished HTTP/2 request(s)");

            // Wait until the connection is closed.
            channel.close().syncUninterruptibly();
        }catch (Exception e){

        }finally {
            workerGroup.shutdownGracefully();
        }

    }

/*
    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
            sslCtx = SslContextBuilder.forClient()
                    .sslProvider(provider)
                *//* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                 * Please refer to the HTTP/2 specification for cipher requirements. *//*
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                    .build();
        } else {
            sslCtx = null;
        }

        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Http2ClientInitializer initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE);

        try {
            // Configure the client.
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.remoteAddress(HOST, PORT);
            b.handler(initializer);

            // Start the client.
            Channel channel = b.connect().syncUninterruptibly().channel();
            System.out.println("Connected to [" + HOST + ':' + PORT + ']');

            // Wait for the HTTP/2 upgrade to occur.
            Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
            http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);

            HttpResponseHandler responseHandler = initializer.responseHandler();
            int streamId = 3;
            HttpScheme scheme = SSL ? HttpScheme.HTTPS : HttpScheme.HTTP;
            AsciiString hostName = new AsciiString(HOST + ':' + PORT);
            System.err.println("Sending request(s)...");
            if (URL != null) {
                // Create a simple GET request.
                FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, URL);
                request.headers().add(HttpHeaderNames.HOST, hostName);
                request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
                responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
                streamId += 2;
            }
            if (URL2 != null) {
                // Create a simple POST request with a body.
                FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, POST, URL2,
                        Unpooled.copiedBuffer(URL2DATA.getBytes(CharsetUtil.UTF_8)));
                request.headers().add(HttpHeaderNames.HOST, hostName);
                request.headers().add(HttpHeaderNames.CONTENT_TYPE,"text/xml");
                request.headers().add("SOAPAction","urn:getQuote");
                request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
                responseHandler.put(streamId, channel.writeAndFlush(request), channel.newPromise());
              //  streamId += 2;
            }
            responseHandler.awaitResponses(3, TimeUnit.SECONDS);
            System.out.println("Finished HTTP/2 request(s)");

            // Wait until the connection is closed.
            channel.close().syncUninterruptibly();
        } finally {
            workerGroup.shutdownGracefully();
        }
    }

    */

}
