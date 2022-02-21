package com.github.dockerjava.cmd;

import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.DockerRule;

import com.github.mwiede.dockerjava.jsch.JschDockerHttpClient;
import com.github.dockerjava.junit.category.Integration;
import com.github.dockerjava.transport.DockerHttpClient;
import com.jcraft.jsch.JSchException;
import org.junit.Rule;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.time.Duration;

/**
 * @author Kanstantsin Shautsou
 */
@Category(Integration.class)
public abstract class CmdIT {

    public static DockerHttpClient createDockerHttpClient(DockerClientConfig config) {

        DockerHttpClient dockerHttpClient;
        if ("ssh".equalsIgnoreCase(config.getDockerHost().getScheme())) {

            final JschDockerHttpClient.Builder builder = new JschDockerHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .readTimeout(Duration.ofSeconds(20))
                    .sslConfig(config.getSSLConfig())
                    .dockerHost(config.getDockerHost())
                    .useSocket()
                    ;
            try {
                dockerHttpClient = builder.build();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException("This patched class only supports testing ssh protocol scheme");
        }

        return new TrackingDockerHttpClient(dockerHttpClient);
    }

    public static DockerClientImpl createDockerClient(DockerClientConfig config) {
        return (DockerClientImpl) DockerClientBuilder.getInstance(config)
                .withDockerHttpClient(createDockerHttpClient(config))
                .build();
    }

    @Rule
    public DockerRule dockerRule = new DockerRule();

    @Rule
    public DockerHttpClientLeakDetector leakDetector = new DockerHttpClientLeakDetector();
}
