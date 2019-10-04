/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.benchmarks;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Carter Kozak
 */
@State(Scope.Benchmark)
public class SimpleBenchmarkState {
    static {
        System.setProperty("xnio.nio.alt-queued-server", "true");
    }

    private static final int PORT = 4433;

    @SuppressWarnings("unused") // Set by JMH
    @Param("HTTPS")
    private ListenerType listenerType;

    private Undertow undertow;
    private CloseableHttpClient client;
    private String baseUri;

    @Setup
    public final void before() {
        Undertow.Builder builder = Undertow.builder()
                .setIoThreads(computeIoThreadCount())
                .setWorkerThreads(256)
                .setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 10000)
                .setSocketOption(Options.SSL_CLIENT_AUTH_MODE, SslClientAuthMode.NOT_REQUESTED)
                .setHandler(Handlers.routing()
                        /* Responds with N bytes where N is the value of the "size" query parameter. */
                        .get("/blocking", new BlockingHandler(new HttpHandler() {
                            @Override
                            public void handleRequest(HttpServerExchange exchange) throws Exception {
                                String value = exchange.getQueryParameters().get("size").getFirst();
                                int bytes = Integer.parseInt(value);
                                exchange.getResponseHeaders()
                                        .put(Headers.CONTENT_TYPE, "application/octet-stream")
                                        .put(Headers.CONTENT_LENGTH, value);
                                OutputStream out = exchange.getOutputStream();
                                for (int i = 0; i < bytes; i++) {
                                    out.write(1);
                                }
                            }
                        }))
                        /* Responds with the the string value of the number of bytes received. */
                        .post("/blocking", new BlockingHandler(new HttpHandler() {
                            @Override
                            public void handleRequest(HttpServerExchange exchange) throws Exception {
                                InputStream stream = exchange.getInputStream();
                                long length = BenchmarkUtils.length(stream);
                                String stringValue = Long.toString(length);
                                exchange.getResponseHeaders()
                                        .put(Headers.CONTENT_TYPE, "text/plain")
                                        .put(Headers.CONTENT_LENGTH, stringValue.length());
                                exchange.getResponseSender().send(stringValue);
                            }
                        })));
        switch (listenerType) {
            case HTTP:
                builder.addHttpListener(PORT, "0.0.0.0");
                break;
            case HTTPS:
                builder.addHttpsListener(PORT, "0.0.0.0", TLSUtils.newServerContext());
                break;
            default:
                throw new IllegalStateException("Unknown protocol: " + listenerType);
        }

        undertow = builder.build();
        undertow.start();

        client = HttpClients.custom()
                .disableConnectionState()
                .disableAutomaticRetries()
                .setSSLContext(TLSUtils.newClientContext())
                .setMaxConnPerRoute(100)
                .setMaxConnTotal(100)
                // Disable persistent HTTP connections
                .addInterceptorFirst((HttpRequestInterceptor) (request, context) ->
                        request.addHeader("Connection", "close"))
                .build();
        baseUri = (listenerType == ListenerType.HTTP ? "http" : "https") + "://localhost:" + PORT;
    }

    private static int computeIoThreadCount() {
        int logicalCores = Runtime.getRuntime().availableProcessors();
        if (logicalCores % 2 == 0) {
            // https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/commit/?id=07f4c90062f8fc7c8c26f8f95324cbe8fa3145a5
            // From the commit message:
            // > In this patch, I changed the starting point in __inet_hash_connect()
            // > so that we try to favor even [1] ports, leaving odd ports for bind()
            // > users.
            //
            // Which conflicts with the xnio hashing function, which picks an
            // IO thread using (hash(addr) * 23 + port) % IO_THREADS.
            // reference:
            // https://github.com/xnio/xnio/blob/6bf7f1e5b9e56f14db28deea7e38db3f624fbb35/nio-impl/src/main/java/org/xnio/nio/QueuedNioTcpServer.java#L495
            //
            // When the port component is always even, only half of our IO threads will
            // be used.
            //
            // Tracking: https://issues.jboss.org/browse/XNIO-328
            logicalCores--;
        }
        return Math.max(1, logicalCores);
    }

    @TearDown
    public final void after() throws IOException {
        if (undertow != null) {
            undertow.stop();
            undertow = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    public CloseableHttpClient client() {
        return client;
    }

    public String getBaseUri() {
        return baseUri;
    }

    public enum ListenerType {HTTP, HTTPS}
}
