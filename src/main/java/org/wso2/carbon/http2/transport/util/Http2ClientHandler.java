package org.wso2.carbon.http2.transport.util;

import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.axis2.context.MessageContext;

import java.util.*;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * Created by chanakabalasooriya on 9/13/16.
 */
public class Http2ClientHandler extends SimpleChannelInboundHandler<Object> {

    private volatile SortedMap<Integer, Map.Entry<ChannelFuture, ChannelPromise>> streamidPromiseMap;
    private volatile TreeMap<Integer, MessageContext> requests;
    private Log log= LogFactory.getLog(Http2ClientHandler.class);
    private int currentStreamId=3;
    private Channel channel;
    private TargetConfiguration targetConfig;
    private volatile TreeMap<Integer,Http2Response> responseMap;
    private boolean streamIdOverflow=false;

    public void setTenantDomain(String tenantDomain) {
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public void acknowledgeHandshake() throws InterruptedException {
    }


    public Http2ClientHandler() {
        streamidPromiseMap = new TreeMap<Integer, Map.Entry<ChannelFuture, ChannelPromise>>();
        requests=new TreeMap<Integer, MessageContext>();
        responseMap=new TreeMap<>();
    }


    public void put(int streamId, Object request) {
        streamidPromiseMap.put(streamId,
                new AbstractMap.SimpleEntry<ChannelFuture, ChannelPromise>(channel.writeAndFlush(request), channel.newPromise()));
    }

    /**
     * Wait (sequentially) for a time duration for each anticipated response
     *
     * @param timeout Value of time to wait for each response
     * @param unit Units associated with {@code timeout}
     * @see Http2ClientHandler#put(int, ChannelFuture, ChannelPromise)
     */
    public void awaitResponses(long timeout, TimeUnit unit) {
        Iterator<Map.Entry<Integer, Map.Entry<ChannelFuture, ChannelPromise>>> itr = streamidPromiseMap.entrySet().iterator();
        while (itr.hasNext()) {
            Map.Entry<Integer, Map.Entry<ChannelFuture, ChannelPromise>> entry = itr.next();
            ChannelFuture writeFuture = entry.getValue().getKey();
            if (!writeFuture.awaitUninterruptibly(timeout, unit)) {
                throw new IllegalStateException("Timed out waiting to write for stream id " + entry.getKey());
            }
            if (!writeFuture.isSuccess()) {
                throw new RuntimeException(writeFuture.cause());
            }
            ChannelPromise promise = entry.getValue().getValue();
            /*if(entry.getKey()==3){
                itr.remove();
                continue;
            }*/
            if (!promise.awaitUninterruptibly(timeout, unit)) {
                throw new IllegalStateException("Timed out waiting for response on stream id " + entry.getKey());
            }
            if (!promise.isSuccess()) {
                throw new RuntimeException(promise.cause());
            }
            System.out.println("---Stream id: " + entry.getKey() + " received---");
            itr.remove();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Integer  streamId = ((FullHttpResponse)msg).headers().getInt(
                HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());

        if (streamId == null) {
            log.error("Http2ClientHandler unexpected message received: " + msg);
            return;
        }
        /*Map.Entry<ChannelFuture, ChannelPromise> entry = streamidPromiseMap.get(streamId);

        if (entry == null) {
            log.error("Message received for unknown stream id " + streamId);
            return;
        }*/
            MessageContext request=requests.get(streamId);
            if(request==null){
                log.error("Message received without a request");
                return;
            }
            log.info("Respond received for stream id:"+streamId);
            //Get Http2Response message
            Http2Response response;
            if(responseMap.containsKey(streamId)){
                response=responseMap.get(streamId);
            }else {
                if(msg instanceof Http2HeadersFrame){
                    Http2HeadersFrame res=(Http2HeadersFrame)msg;
                    response=new Http2Response(res);
                }
                else{
                    FullHttpResponse res=(FullHttpResponse)msg;
                    response=new Http2Response(res);
                }
                responseMap.put(streamId,response);
            }
            if(msg instanceof Http2DataFrame){
                response.setDataFrame((Http2DataFrame)msg);
            }
          //  entry.getValue().setSuccess();

            if(response.isEndOfStream()){
                Http2ClientWorker clientWorker=new Http2ClientWorker(targetConfig,request,response);
                clientWorker.injectToAxis2Engine();
                requests.remove(streamId);
                responseMap.remove(streamId);
                streamidPromiseMap.remove(streamId);
            }

    }

    public int getStreamId() {
        int returnId=currentStreamId;
        if(currentStreamId>Integer.MAX_VALUE-10){   //Max stream_id is Integer.Max-10
            streamIdOverflow=true;
        }
        currentStreamId+=2;
        return returnId;
    }

    public boolean isStreamIdOverflow() {
        return streamIdOverflow;
    }

    public synchronized void setRequest(int streamId, MessageContext msgCtx){
        requests.put(streamId,msgCtx);
    }
    public void setTargetConfig(TargetConfiguration targetConfiguration) {
        this.targetConfig=targetConfiguration;
    }
}
