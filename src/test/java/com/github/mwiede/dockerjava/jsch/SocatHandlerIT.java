package com.github.mwiede.dockerjava.jsch;

import com.github.dockerjava.api.model.Container;
import com.github.mwiede.dockerjava.jsch.SocatHandler;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.OpenSSHConfig;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.Slf4jLogger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test relies on ~/.ssh/config which needs an entry for Host setup in the env variable DOCKER_HOST
 * <p>
 * config could look like:
 * <p>
 * <pre>
 * Host junit-host
 * HostName foo
 * StrictHostKeyChecking no
 * User bar
 * IdentityFile ~/.ssh/some_private_key
 * PreferredAuthentications publickey
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "DOCKER_HOST", matches = "ssh://.*")
class SocatHandlerIT {

    private static Session session;
    private Container container;

    @BeforeAll
    static void init() throws JSchException, IOException, URISyntaxException {
        final JSch jSch = new JSch();
        JSch.setLogger(new Slf4jLogger());
        final String configFile = System.getProperty("user.home") + File.separator + ".ssh" + File.separator + "config";
        final File file = new File(configFile);
        if (file.exists()) {
            jSch.setConfigRepository(OpenSSHConfig.parseFile(file.getAbsolutePath()));
        }
        final String knownHostsFile = System.getProperty("user.home") + File.separator + ".ssh" + File.separator + "known_hosts";
        if (new File(knownHostsFile).exists()) {
            jSch.setKnownHosts(knownHostsFile);
        }
        URI dockerHost = new URI(System.getenv("DOCKER_HOST"));
        session = jSch.getSession(null, dockerHost.getHost(), dockerHost.getPort() > -1 ? dockerHost.getPort() : 22);
        session.connect(1500);
    }

    @AfterAll
    static void close() {
        session.disconnect();
    }

    @AfterEach
    void stopSocat() throws IOException, JSchException {
        if (container != null) {
            SocatHandler.stopSocat(session, container.getId(), System.getenv("JSCH_DOCKER_ADDITIONAL_FILE_TO_SOURCE"));
        }
    }

    @org.junit.jupiter.api.Test
    @Timeout(value = 20)
    void startSocatAndPing() throws IOException, JSchException {
        container = SocatHandler.startSocat(session, "", "", System.getenv("JSCH_DOCKER_ADDITIONAL_FILE_TO_SOURCE"));
        assertNotNull(container);
        assertEquals("200", ping(container));
    }

    private String ping(Container container) throws JSchException, IOException {
        final Channel streamForwarder = session.getStreamForwarder(container.getPorts()[0].getIp(), container.getPorts()[0].getPublicPort());
        streamForwarder.connect(100);
        String cmd = "GET /_ping HTTP/1.0\r\n\r\n";
        final PrintWriter printWriter = new PrintWriter(streamForwarder.getOutputStream());
        printWriter.println(cmd);
        printWriter.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(streamForwarder.getInputStream()));
        for (String line; (line = reader.readLine()) != null; ) {
            final Matcher matcher = Pattern.compile("HTTP/\\d\\.\\d (\\d+) \\w+").matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        fail("could not find response code");
        return null;
    }
}
