package com.github.dockerjava.cmd.swarm;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.utils.LogContainerTestCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.cmd.CmdIT;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.junit.category.Integration;
import com.github.dockerjava.junit.category.SwarmModeIntegration;
import com.github.mwiede.dockerjava.jsch.JschDockerHttpClient;
import com.jcraft.jsch.JSchException;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.dockerjava.api.model.HostConfig.newHostConfig;
import static com.github.dockerjava.core.RemoteApiVersion.VERSION_1_24;
import static com.github.dockerjava.junit.DockerMatchers.isGreaterOrEqual;
import static org.awaitility.Awaitility.await;

@Category({SwarmModeIntegration.class, Integration.class})
public abstract class SwarmCmdIT extends CmdIT {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmCmdIT.class);
    private static final String DOCKER_IN_DOCKER_IMAGE_REPOSITORY = "docker";

    private static final String DOCKER_IN_DOCKER_IMAGE_TAG = "26.1.3-dind";

    private static final String DOCKER_IN_DOCKER_CONTAINER_PREFIX = "docker";

    private static final String NETWORK_NAME = "dind-network";

    private final AtomicInteger numberOfDockersInDocker = new AtomicInteger();

    private final Set<String> startedContainerIds = new HashSet<>();

    @Before
    public final void setUpMultiNodeSwarmCmdIT() throws Exception {
        Assume.assumeThat(dockerRule, isGreaterOrEqual(VERSION_1_24));
    }

    protected DockerClient startSwarm() {
        DockerClient dockerClient;
        try {
            dockerClient = startDockerInDocker();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        dockerClient.initializeSwarmCmd(new SwarmSpec()).exec();
        return dockerClient;
    }

    @After
    public final void tearDownMultiNodeSwarmCmdIT() {
        for (String containerId : startedContainerIds) {
            try {
                dockerRule.getClient().removeContainerCmd(containerId).withForce(true).exec();
            } catch (NotFoundException e) {
                // container does not exist
            }
        }

        try {
            dockerRule.getClient().removeNetworkCmd(NETWORK_NAME).exec();
        } catch (NotFoundException e) {
            // network does not exist
        }
    }

    protected DockerClient startDockerInDocker() throws InterruptedException {
        // Create network if not already exists
        DockerClient hostDockerClient = dockerRule.getClient();
        try {
            hostDockerClient.inspectNetworkCmd().withNetworkId(NETWORK_NAME).exec();
        } catch (NotFoundException e) {
            try {
                hostDockerClient.createNetworkCmd().withName(NETWORK_NAME).exec();
            } catch (ConflictException e2) {
                // already exists
            }
        }

        try (
                ResultCallback.Adapter<PullResponseItem> callback = hostDockerClient.pullImageCmd(DOCKER_IN_DOCKER_IMAGE_REPOSITORY)
                        .withTag(DOCKER_IN_DOCKER_IMAGE_TAG)
                        .start()
        ) {
            callback.awaitCompletion();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ExposedPort exposedPort = ExposedPort.tcp(2375);
        CreateContainerResponse response = hostDockerClient
                .createContainerCmd(DOCKER_IN_DOCKER_IMAGE_REPOSITORY + ":" + DOCKER_IN_DOCKER_IMAGE_TAG)
            .withEntrypoint("dockerd")
            .withCmd(Arrays.asList("--host=tcp://0.0.0.0:2375", "--host=unix:///var/run/docker.sock", "--tls=false"))
                .withHostConfig(newHostConfig()
                        .withNetworkMode(NETWORK_NAME)
                        .withPortBindings(new PortBinding(
                                Ports.Binding.bindIp("127.0.0.1"),
                                exposedPort))
                .withPrivileged(true))
                .withAliases(DOCKER_IN_DOCKER_CONTAINER_PREFIX + numberOfDockersInDocker.incrementAndGet())
                .exec();

        String containerId = response.getId();
        startedContainerIds.add(containerId);

        hostDockerClient.startContainerCmd(containerId).exec();

        InspectContainerResponse inspectContainerResponse = hostDockerClient.inspectContainerCmd(containerId).exec();

        Ports.Binding binding = inspectContainerResponse.getNetworkSettings().getPorts().getBindings().get(exposedPort)[0];

        DockerClient dockerClient = initializeDockerClient(binding); 

        await().pollDelay(Duration.ofSeconds(5)).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            dockerClient.pingCmd().exec();
        });

        return dockerClient;
    }

    private void logDindContainerOutput(DockerClient hostDockerClient, String containerId) throws java.lang.InterruptedException{
        LogContainerTestCallback loggingCallback = new LogContainerTestCallback(true);
        hostDockerClient.logContainerCmd(containerId).withStdErr(true).withStdOut(true).withFollowStream(true).withTailAll().exec(loggingCallback);
        loggingCallback.awaitCompletion(20L, TimeUnit.SECONDS);
        LOG.debug(loggingCallback.toString());
    }

    private DockerClient initializeDockerClient(Ports.Binding binding) {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withRegistryUrl("https://index.docker.io/v1/")
                .withDockerHost("tcp://" + binding).build();

        if (!dockerRule.getConfig().getDockerHost().getScheme().equals(config.getDockerHost().getScheme())) {

            try {
                JschDockerHttpClient dockerHttpClient = new JschDockerHttpClient.Builder()
                        .sslConfig(config.getSSLConfig())
                        .dockerHost(dockerRule.getConfig().getDockerHost()) // connect via ssh to host
                        .useTcp(config.getDockerHost().getPort()) // and then use the tcp port to connect to dind container
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(Duration.ofSeconds(15))
                        .build();

                return DockerClientBuilder.getInstance(dockerRule.getConfig())
                        .withDockerHttpClient(dockerHttpClient)
                        .build();
            } catch (IOException | JSchException e) {
                throw new RuntimeException(e);
            }
        } else {
            return createDockerClient(config);
        }
    }
}
