package org.wso2.carbon.http2.transport.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.CharsetUtil;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.engine.MessageReceiver;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.axis2.context.MessageContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.MessageContextCreatorForAxis2;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.mediators.MediatorFaultHandler;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.http2.transport.service.ServiceReferenceHolder;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * Created by chanakabalasooriya on 9/13/16.
 */
public class Http2ClientHandler extends SimpleChannelInboundHandler<Object> {

    private SortedMap<Integer, Map.Entry<ChannelFuture, ChannelPromise>> streamidPromiseMap;
    private TreeMap<Integer, MessageContext> requests;
    private Log log= LogFactory.getLog(Http2ClientHandler.class);
    private int currentStreamId=3;
    private Channel channel;
    private TargetConfiguration targetConfig;
    private TreeMap<Integer,Http2Response> responseMap;

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

    /**
     * Create an association between an anticipated response stream id and a {@link ChannelPromise}
     *
     * @param streamId The stream for which a response is expected
     * @param writeFuture A future that represent the request write operation
     * @param promise The promise object that will be used to wait/notify events
     * @return The previous object associated with {@code streamId}
     * @see Http2ClientHandler#awaitResponses(long, TimeUnit)
     */
    public Map.Entry<ChannelFuture, ChannelPromise> put(int streamId, ChannelFuture writeFuture, ChannelPromise promise) {
        return streamidPromiseMap.put(streamId,
                new AbstractMap.SimpleEntry<ChannelFuture, ChannelPromise>(writeFuture, promise));
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
        Map.Entry<ChannelFuture, ChannelPromise> entry = streamidPromiseMap.get(streamId);

        if (entry == null) {
            log.error("Message received for unknown stream id " + streamId);
        } else {
            MessageContext request=requests.get(streamId);
            if(request==null){
                log.error("Message received without a request");
                return;
            }

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
            entry.getValue().setSuccess();

            if(response.isEndOfStream()){
                Http2ClientWorker clientWorker=new Http2ClientWorker(targetConfig,request,response);
                clientWorker.injexttoAxis2Engine();
                requests.remove(streamId);
                responseMap.remove(streamId);
                streamidPromiseMap.remove(streamId);
            }
        }
    }

    public synchronized int getStreamId() {
        int returnId=currentStreamId;
        currentStreamId+=2;
        return returnId;
    }

    public void setRequest(int streamId,MessageContext msgCtx){
        requests.put(streamId,msgCtx);
    }


    public void setTargetConfig(TargetConfiguration targetConfiguration) {
        this.targetConfig=targetConfiguration;
    }
}

//Take response and send back to Synapse
/*
This class should have a request context and it should be mapped with stream id;
when sending a message
    * get the message
    * do formating as needed
    * check for a available channel;
    * send the request;
    * update the response handlers for that request;

when a response received
    * get the corresponding request
    * set evelope for the messageContext
    * inject to the sequence
    *
 */

/*
 private org.apache.synapse.MessageContext getSynapseMessageContext(String tenantDomain) throws AxisFault {
        org.apache.synapse.MessageContext synCtx = createSynapseMessageContext(tenantDomain);
        if (responseSender != null) {
            synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
            synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
        }
        synCtx.setProperty(WebsocketConstants.WEBSOCKET_SUBSCRIBER_PATH, handshaker.uri().toString());
        return synCtx;
                }

private static org.apache.synapse.MessageContext createSynapseMessageContext(String tenantDomain) throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MsgCtx = createAxis2MessageContext();
        ServiceContext svcCtx = new ServiceContext();
        OperationContext opCtx = new OperationContext(new InOutAxisOperation(), svcCtx);
        axis2MsgCtx.setServiceContext(svcCtx);
        axis2MsgCtx.setOperationContext(opCtx);
        if (!tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
        ConfigurationContext tenantConfigCtx =
        TenantAxisUtils.getTenantConfigurationContext(tenantDomain,
        axis2MsgCtx.getConfigurationContext());
        axis2MsgCtx.setConfigurationContext(tenantConfigCtx);
        axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, tenantDomain);
        } else {
        axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN,
        MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        }
        SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
        SOAPEnvelope envelope = fac.getDefaultEnvelope();
        axis2MsgCtx.setEnvelope(envelope);
        return MessageContextCreatorForAxis2.getSynapseMessageContext(axis2MsgCtx);
        }


private static org.apache.axis2.context.MessageContext createAxis2MessageContext() {
        org.apache.axis2.context.MessageContext axis2MsgCtx = new org.apache.axis2.context.MessageContext();
        axis2MsgCtx.setMessageID(UIDGenerator.generateURNString());
        axis2MsgCtx.setConfigurationContext(ServiceReferenceHolder.getInstance().getConfigurationContextService()
        .getServerConfigContext());
        axis2MsgCtx.setProperty(org.apache.axis2.context.MessageContext.CLIENT_API_NON_BLOCKING,
        Boolean.FALSE);
        axis2MsgCtx.setServerSide(true);
        return axis2MsgCtx;
        }
        */
 /*private void injectToSequence(org.apache.synapse.MessageContext synCtx,
                                  String dispatchSequence, String dispatchErrorSequence) {
        SequenceMediator injectingSequence = null;
        if (dispatchSequence != null) {
            injectingSequence = (SequenceMediator) synCtx.getSequence(dispatchSequence);
        }
        if (injectingSequence == null) {
            injectingSequence = (SequenceMediator) synCtx.getMainSequence();
        }
        SequenceMediator faultSequence = getFaultSequence(synCtx, dispatchErrorSequence);
        MediatorFaultHandler mediatorFaultHandler = new MediatorFaultHandler(faultSequence);
        synCtx.pushFaultHandler(mediatorFaultHandler);
        if (log.isDebugEnabled()) {
            log.debug("injecting message to sequence : " + dispatchSequence);
        }
        synCtx.getEnvironment().injectMessage(synCtx, injectingSequence);
    }*/

// private SequenceMediator getFaultSequence(org.apache.synapse.MessageContext synCtx,

   /* private MessageContext createResponseContext(){

    }*/

            /*org.apache.synapse.MessageContext synCtx = getSynapseMessageContext((String)request.getProperty(MultitenantConstants.TENANT_DOMAIN));

          //  String message = ((TextWebSocketFrame) frame).text();

            org.apache.axis2.context.MessageContext axis2MsgCtx =
                    ((org.apache.synapse.core.axis2.Axis2MessageContext) synCtx)
                            .getAxis2MessageContext();

            Builder builder = null;
            if (contentType == null) {
                log.debug("No content type specified. Using SOAP builder.");
                builder = new SOAPBuilder();
            } else {
                int index = contentType.indexOf(';');
                String type = index > 0 ? contentType.substring(0, index)
                        : contentType;
                try {
                    builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
                } catch (AxisFault axisFault) {
                    log.error("Error while creating message builder :: "
                            + axisFault.getMessage());
                }
                if (builder == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("No message builder found for type '" + type
                                + "'. Falling back to SOAP.");
                    }
                    builder = new SOAPBuilder();
                }
            }

            OMElement documentElement = null;
            InputStream in = new AutoCloseInputStream(new ByteArrayInputStream(response.getBytes()));
            documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            synCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            AxisEngine.receive(axis2MsgCtx);
*/