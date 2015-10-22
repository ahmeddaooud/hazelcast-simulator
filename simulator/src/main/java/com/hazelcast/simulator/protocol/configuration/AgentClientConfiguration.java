package com.hazelcast.simulator.protocol.configuration;

import com.hazelcast.simulator.protocol.connector.AgentConnector;
import com.hazelcast.simulator.protocol.core.ConnectionManager;
import com.hazelcast.simulator.protocol.core.ResponseFuture;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.handler.ExceptionHandler;
import com.hazelcast.simulator.protocol.handler.ForwardToCoordinatorHandler;
import com.hazelcast.simulator.protocol.handler.MessageConsumeHandler;
import com.hazelcast.simulator.protocol.handler.MessageEncoder;
import com.hazelcast.simulator.protocol.handler.ResponseEncoder;
import com.hazelcast.simulator.protocol.handler.ResponseHandler;
import com.hazelcast.simulator.protocol.handler.SimulatorFrameDecoder;
import com.hazelcast.simulator.protocol.handler.SimulatorProtocolDecoder;
import com.hazelcast.simulator.protocol.processors.AgentOperationProcessor;
import io.netty.channel.ChannelPipeline;

import java.util.concurrent.ConcurrentMap;

public class AgentClientConfiguration extends AbstractClientConfiguration {

    private final SimulatorAddress localAddress;

    private final ForwardToCoordinatorHandler forwardToCoordinatorHandler;
    private final MessageConsumeHandler messageConsumeHandler;
    private final ExceptionHandler exceptionHandler;
    private final ConnectionManager connectionManager;

    public AgentClientConfiguration(AgentConnector agentConnector, AgentOperationProcessor processor,
                                    ConcurrentMap<String, ResponseFuture> futureMap, SimulatorAddress localAddress,
                                    int workerIndex, String workerHost, int workerPort, ConnectionManager connectionManager) {
        super(futureMap, localAddress, workerIndex, workerHost, workerPort);
        this.localAddress = localAddress;
        this.connectionManager = connectionManager;

        this.forwardToCoordinatorHandler = new ForwardToCoordinatorHandler(localAddress, connectionManager,
                processor.getWorkerJVMs());
        this.messageConsumeHandler = new MessageConsumeHandler(localAddress, processor);
        this.exceptionHandler = new ExceptionHandler(agentConnector);
    }

    @Override
    public void configurePipeline(ChannelPipeline pipeline) {
        pipeline.addLast("responseEncoder", new ResponseEncoder(localAddress));
        pipeline.addLast("messageEncoder", new MessageEncoder(localAddress, getRemoteAddress()));
        pipeline.addLast("frameDecoder", new SimulatorFrameDecoder());
        pipeline.addLast("protocolDecoder", new SimulatorProtocolDecoder(localAddress));
        pipeline.addLast("forwardToCoordinatorHandler", forwardToCoordinatorHandler);
        pipeline.addLast("responseHandler", new ResponseHandler(localAddress, getRemoteAddress(), getFutureMap()));
        pipeline.addLast("messageConsumeHandler", messageConsumeHandler);
        pipeline.addLast("exceptionHandler", exceptionHandler);
    }

    @Override
    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
