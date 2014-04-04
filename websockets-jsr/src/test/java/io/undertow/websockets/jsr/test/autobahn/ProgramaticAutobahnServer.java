/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.undertow.websockets.jsr.test.autobahn;

import io.undertow.server.protocol.http.HttpOpenListener;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.core.CompositeThreadSetupAction;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.websockets.jsr.JsrWebSocketFilter;
import io.undertow.websockets.jsr.ServerEndpointConfigImpl;
import io.undertow.websockets.jsr.ServerWebSocketContainer;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.StreamConnection;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;

import javax.servlet.DispatcherType;
import java.net.InetSocketAddress;
import java.util.Collections;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public class ProgramaticAutobahnServer implements Runnable {

    private static ServerWebSocketContainer deployment;

    private final int port;

    public ProgramaticAutobahnServer(final int port) {
        this.port = port;
    }

    public void run() {

        Xnio xnio = Xnio.getInstance();
        try {

            XnioWorker worker = xnio.createWorker(OptionMap.builder()
                    .set(Options.WORKER_WRITE_THREADS, 4)
                    .set(Options.WORKER_READ_THREADS, 4)
                    .set(Options.CONNECTION_HIGH_WATER, 1000000)
                    .set(Options.CONNECTION_LOW_WATER, 1000000)
                    .set(Options.WORKER_TASK_CORE_THREADS, 10)
                    .set(Options.WORKER_TASK_MAX_THREADS, 12)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.CORK, true)
                    .getMap());

            OptionMap serverOptions = OptionMap.builder()
                    .set(Options.WORKER_ACCEPT_THREADS, 4)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.REUSE_ADDRESSES, true)
                    .getMap();
            HttpOpenListener openListener = new HttpOpenListener(new ByteBufferSlicePool(BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR, 8192, 8192 * 8192), 8192);
            ChannelListener acceptListener = ChannelListeners.openListenerAdapter(openListener);
           AcceptingChannel<StreamConnection> server = worker.createStreamConnectionServer(new InetSocketAddress(port), acceptListener, serverOptions);

            server.resumeAccepts();

            final ServletContainer container = ServletContainer.Factory.newInstance();

            ServerWebSocketContainer deployment = new ServerWebSocketContainer(TestClassIntrospector.INSTANCE, worker, new ByteBufferSlicePool(100, 1000),new CompositeThreadSetupAction(Collections.EMPTY_LIST), true, false);
            DeploymentInfo builder = new DeploymentInfo()
                    .setClassLoader(ProgramaticAutobahnServer.class.getClassLoader())
                    .setContextPath("/")
                    .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                    .setDeploymentName("servletContext.war")
                    .addServletContextAttribute(javax.websocket.server.ServerContainer.class.getName(), deployment)
                    .addFilter(new FilterInfo("filter", JsrWebSocketFilter.class))
                    .addFilterUrlMapping("filter", "/*", DispatcherType.REQUEST);

            deployment.addEndpoint(new ServerEndpointConfigImpl(ProgramaticAutobahnEndpoint.class, "/"));

            DeploymentManager manager = container.addDeployment(builder);
            manager.deploy();


            openListener.setRootHandler(manager.start());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static void main(String[] args) {
        new ProgramaticAutobahnServer(7777).run();
    }

}
