package org.lognet.springboot.grpc;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.examples.GreeterGrpc;
import io.grpc.examples.GreeterOuterClass;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.lognet.springboot.grpc.autoconfigure.GRpcServerProperties;
import org.lognet.springboot.grpc.context.LocalRunningGrpcPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@ContextConfiguration(
        initializers = GrpcServerTestBase.TessAppContextInitializer.class)
public abstract class GrpcServerTestBase {

    static class TessAppContextInitializer implements
            ApplicationContextInitializer<GenericApplicationContext> {

        @Override
        public void initialize(GenericApplicationContext applicationContext) {
            applicationContext.setAllowCircularReferences(false);
        }
    }

    @Autowired(required = false)
    @Qualifier("grpcServerRunner")
    protected GRpcServerRunner grpcServerRunner;

    @Autowired(required = false)
    @Qualifier("grpcInprocessServerRunner")
    protected GRpcServerRunner grpcInprocessServerRunner;

    protected ManagedChannel channel;
    protected ManagedChannel inProcChannel;
    protected Channel selectedChanel;

    @LocalRunningGrpcPort
    protected  int runningPort;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected GRpcServicesRegistry registry;

    @Autowired
    protected GRpcServerProperties gRpcServerProperties;

    protected String name="John";

    @Before
    public   void setupChannels() throws IOException {
        if(gRpcServerProperties.isEnabled()) {
            ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress("localhost", getPort());
            Resource certChain = Optional.ofNullable(gRpcServerProperties.getSecurity())
                    .map(GRpcServerProperties.SecurityProperties::getCertChain)
                    .orElse(null);
            if(null!= certChain){

                setupTransportSecurity(channelBuilder,certChain);

            }else{
                channelBuilder.usePlaintext();
            }


            channel = onChannelBuild(channelBuilder).build();
        }
        if(StringUtils.hasText(gRpcServerProperties.getInProcessServerName())){
            inProcChannel = onChannelBuild(
                                InProcessChannelBuilder.forName(gRpcServerProperties.getInProcessServerName())
                                .usePlaintext()
                            ).build();

        }
        selectedChanel = getChannel();
    }

    protected void setupTransportSecurity(ManagedChannelBuilder<?> channelBuilder, Resource certChain) throws IOException {
        SslContext sslContext = GrpcSslContexts.forClient().trustManager(certChain.getInputStream()).build();
        ((NettyChannelBuilder)channelBuilder)
                .useTransportSecurity()
                .sslContext(sslContext);
    }
    protected  Channel getChannel(){
       return Optional.ofNullable(channel).orElse(inProcChannel);
    }
    protected int getPort(){
        return runningPort;
    }

    protected ManagedChannelBuilder<?>  onChannelBuild(ManagedChannelBuilder<?> channelBuilder){
        return  channelBuilder;
    }

    protected InProcessChannelBuilder onChannelBuild(InProcessChannelBuilder channelBuilder){
        return  channelBuilder;
    }

    @After
    public void shutdownChannels() {
        Optional.ofNullable(channel).ifPresent(ManagedChannel::shutdownNow);
        Optional.ofNullable(inProcChannel).ifPresent(ManagedChannel::shutdownNow);
    }

    protected List<String> appServicesNames(){
        return registry.getServiceNameToServiceBeanMap()
                .keySet()
                .stream()
                .filter(name-> !name.equals(HealthGrpc.SERVICE_NAME) && !name.equals(ServerReflectionGrpc.SERVICE_NAME))
                .collect(Collectors.toList());
    }
    @Test
    public void simpleGreeting() throws  Exception {



        final GreeterGrpc.GreeterFutureStub greeterFutureStub = GreeterGrpc.newFutureStub(selectedChanel);
        final GreeterOuterClass.HelloRequest helloRequest =GreeterOuterClass.HelloRequest.newBuilder().setName(name).build();
        final String reply = beforeGreeting(greeterFutureStub).sayHello(helloRequest).get().getMessage();
        assertNotNull("Reply should not be null",reply);
        assertTrue(String.format("Reply should contain name '%s'",name),reply.contains(name));
        afterGreeting();

    }

    protected GreeterGrpc.GreeterFutureStub beforeGreeting(GreeterGrpc.GreeterFutureStub stub) {
        return  stub;
    }

    protected void afterGreeting() throws Exception {

    }
}
