/**
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */
package io.pelle.hivemq.plugin;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.hivemq.spi.callback.cluster.ClusterDiscoveryCallback;
import com.hivemq.spi.callback.cluster.ClusterNodeAddress;
import com.hivemq.spi.services.PluginExecutorService;
import com.orbitz.consul.Consul;
import com.orbitz.consul.NotRegisteredException;
import com.orbitz.consul.model.agent.ImmutableRegistration;
import com.orbitz.consul.model.agent.Registration;
import com.orbitz.consul.model.health.ServiceHealth;
import com.orbitz.consul.option.ImmutableQueryOptions;
import com.orbitz.consul.option.QueryOptions;
import io.pelle.hivemq.plugin.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ConsulDiscoveryCallback implements ClusterDiscoveryCallback {

    private static final Logger log = LoggerFactory.getLogger(ConsulDiscoveryCallback.class);

    private final Configuration configuration;
    private final PluginExecutorService pluginExecutorService;
    private final Consul consul;

    private String clusterId;
    private ClusterNodeAddress ownAddress;

    @Inject
    public ConsulDiscoveryCallback(final Consul consul,
                                   final Configuration configuration,
                                   final PluginExecutorService pluginExecutorService) {
        this.consul = consul;
        this.configuration = configuration;
        this.pluginExecutorService = pluginExecutorService;
    }

    private String getServiceId() {
        return String.format("%s", configuration.getConsulServiceName());
    }

    @Override
    public void init(final String clusterId, final ClusterNodeAddress ownAddress) {

        this.clusterId = clusterId;
        this.ownAddress = ownAddress;

        Registration.RegCheck ttlCheck = Registration.RegCheck.ttl(configuration.getConsulCheckTTL());
        Registration serviceRegistration = ImmutableRegistration.builder()
                .name(configuration.getConsulServiceName())
                .port(ownAddress.getPort())
                .address(ownAddress.getHost())
                .id(getServiceId())
                .addChecks(ttlCheck).build();

        ImmutableQueryOptions.Builder queryOptions = ImmutableQueryOptions.builder();

        if (System.getenv(Constants.CONSUL_TOKEN_ENVIRONMENT) != null) {
            queryOptions.token(System.getenv(Constants.CONSUL_TOKEN_ENVIRONMENT));
            log.info("using token for service registration");
        }

        consul.agentClient().register(serviceRegistration,  queryOptions.build());

        pluginExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    consul.agentClient().pass(getServiceId());
                } catch (NotRegisteredException e) {
                    log.error("error updating check", e);
                }
            }
        }, 0, configuration.getConsulUpdateInterval(), TimeUnit.SECONDS);
    }

    @Override
    public ListenableFuture<List<ClusterNodeAddress>> getNodeAddresses() {

        final List<ClusterNodeAddress> addresses = new ArrayList<>();

        List<ServiceHealth> nodes = consul.healthClient()
                .getHealthyServiceInstances(configuration.getConsulServiceName())
                .getResponse();

        for (ServiceHealth node : nodes) {
            addresses.add(new ClusterNodeAddress(node.getService().getAddress(), node.getService().getPort()));
        }

        return Futures.immediateFuture(addresses);
    }

    @Override
    public void destroy() {
        consul.agentClient().deregister(getServiceId());
    }

}
