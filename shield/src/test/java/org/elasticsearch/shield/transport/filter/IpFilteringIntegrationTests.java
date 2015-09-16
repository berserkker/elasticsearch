/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.filter;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ShieldIntegTestCase;
import org.elasticsearch.transport.Transport;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import static org.elasticsearch.test.ESIntegTestCase.Scope;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

// no client nodes, no transport clients, as they all get rejected on network connections
@ClusterScope(scope = Scope.SUITE, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class IpFilteringIntegrationTests extends ShieldIntegTestCase {

    private static int randomClientPort;

    @BeforeClass
    public static void getRandomPort() {
        randomClientPort = randomIntBetween(49000, 65500); // ephemeral port
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        String randomClientPortRange = randomClientPort + "-" + (randomClientPort+100);
        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(Node.HTTP_ENABLED, true)
                .put("transport.profiles.client.port", randomClientPortRange)
                .put("transport.profiles.client.bind_host", "localhost") // make sure this is "localhost", no matter if ipv4 or ipv6, but be consistent
                .put("transport.profiles.client.shield.filter.deny", "_all")
                .put("shield.http.filter.deny", "_all").build();
    }

    @Test
    public void testThatIpFilteringIsIntegratedIntoNettyPipelineViaHttp() throws Exception {
        TransportAddress transportAddress = randomFrom(internalCluster().getDataNodeInstance(HttpServerTransport.class).boundAddress().boundAddresses());
        assertThat(transportAddress, is(instanceOf(InetSocketTransportAddress.class)));
        InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) transportAddress;

        try (Socket socket = new Socket()){
            trySocketConnection(socket, inetSocketTransportAddress.address());
            assertThat(socket.isClosed(), is(true));
        }
    }

    @Test
    public void testThatIpFilteringIsNotAppliedForDefaultTransport() throws Exception {
        Client client = internalCluster().transportClient();
        assertGreenClusterState(client);
    }

    @Test
    public void testThatIpFilteringIsAppliedForProfile() throws Exception {
        try (Socket socket = new Socket()){
            trySocketConnection(socket, new InetSocketAddress(InetAddress.getLoopbackAddress(), getProfilePort("client")));
            assertThat(socket.isClosed(), is(true));
        }
    }

    private void trySocketConnection(Socket socket, InetSocketAddress address) throws IOException {
        logger.info("connecting to {}", address);
        socket.connect(address, 500);

        assertThat(socket.isConnected(), is(true));
        try (OutputStream os = socket.getOutputStream()) {
            os.write("fooooo".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static int getProfilePort(String profile) {
        TransportAddress transportAddress = randomFrom(internalCluster().getInstance(Transport.class).profileBoundAddresses().get(profile).boundAddresses());
        assert transportAddress instanceof InetSocketTransportAddress;
        return ((InetSocketTransportAddress)transportAddress).address().getPort();
    }
}
