package com.github.mwiede.dockerjava.jsch;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.jcraft.jsch.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Objects;

class JschSocket extends Socket {

    private static final Logger logger = LoggerFactory.getLogger(JschSocket.class);

    private final JschDockerConfig config;
    private final Session session;

    private Channel channel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Container socatContainer;

    JschSocket(Session session, JschDockerConfig config) {
        this.session = session;
        this.config = config;
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        connect(timeout);
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public boolean isClosed() {
        return channel != null && channel.isClosed();
    }

    private void connect(int timeout) throws IOException {
        try {
            if (config.isUseTcp()) {
                final int port = config.getTcpPort() != null ? config.getTcpPort() : 2375;
                channel = session.getStreamForwarder("127.0.0.1", port);
                logger.debug("Using channel direct-tcpip with 127.0.0.1:{}", port);
            } else if (config.isUseSocat() || unixSocketOnWindows()) {
                // forward docker socket via socat
                socatContainer = SocatHandler.startSocat(session, config.getSocatFlags(), config.getSocketPath(), config.getAdditionalFileToSource());
                final ContainerPort containerPort = socatContainer.getPorts()[0];
                Objects.requireNonNull(containerPort);
                channel = session.getStreamForwarder(containerPort.getIp(), containerPort.getPublicPort());
                logger.debug("Using channel direct-tcpip with socat on port {}", containerPort.getPublicPort());
            } else if (config.isUseSocket()) {
                // directly forward docker socket
                channel = session.openChannel("direct-streamlocal@openssh.com");
                ((ChannelDirectStreamLocal) channel).setSocketPath(config.getSocketPath());
                logger.debug("Using channel direct-streamlocal on {}", config.getSocketPath());
            } else {
                // only 18.09 and up
                channel = session.openChannel("exec");
                String command ="docker system dial-stdio";
                if (StringUtils.isNotEmpty(config.getAdditionalFileToSource())) {
                    command = " source " + config.getAdditionalFileToSource() + " && " + command;
                }
                ((ChannelExec) channel).setCommand(command);
                logger.debug("Using dialer command");
            }

            inputStream = channel.getInputStream();
            outputStream = channel.getOutputStream();

            channel.connect(timeout);

        } catch (JSchException e) {
            throw new IOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (socatContainer != null) {
            try {
                SocatHandler.stopSocat(session, socatContainer.getId(),config.getAdditionalFileToSource());
            } catch (JSchException e) {
                throw new IOException(e);
            }
        }
        channel.disconnect();
    }

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    private boolean unixSocketOnWindows() {
        return config.isUseSocket() && config.getSocketPath().equalsIgnoreCase(JschDockerConfig.VAR_RUN_DOCKER_SOCK) && isWindowsHost();
    }

    private boolean isWindowsHost() {
        final String serverVersion = session.getServerVersion();
        return serverVersion.toLowerCase(Locale.getDefault()).contains("windows");
    }
}
