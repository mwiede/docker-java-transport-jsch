# docker-java-transport-jsch

[![Maven Central](https://img.shields.io/maven-central/v/com.github.mwiede.dockerjava/docker-java-transport-jsch)](https://maven-badges.herokuapp.com/maven-central/com.github.mwiede.dockerjava/docker-java-transport-jsch)
[![Java CI with Maven](https://github.com/mwiede/docker-java-transport-jsch/actions/workflows/maven.yml/badge.svg)](https://github.com/mwiede/docker-java-transport-jsch/actions/workflows/maven.yml)

## background

This module contains a [docker-java](https://github.com/docker-java/docker-java) transport, which supports ssh protocol.
Since the PR [#1440](https://github.com/docker-java/docker-java/pull/1440) was not accepted, the same code is released
here as independant package. Also see [#1130](https://github.com/docker-java/docker-java/issues/1130) for the original
feature request.

The module uses a [fork of jsch](https://github.com/mwiede/jsch) as java ssh implementation and okhttp as httpclient.

While native docker cli supports ssh connections since Host docker version 18.09 [<sup>1</sup>](#1), with different
options it is possible to make it work for older versions. This library opens the ssh connection and then forwards the
docker daemon socket to make it available to the http client.

The default ssh connection configuration relies on basic [ssh config file](https://www.ssh.com/ssh/config/) in ~/.ssh/config.

## usage

Its basically the same as described
at [getting_started](https://github.com/docker-java/docker-java/blob/master/docs/getting_started.md)
from [docker-java](https://github.com/docker-java/docker-java).

Once you have set up public key authentication and `DOCKER_HOST` you can

```java
try(final JschDockerHttpClient httpClient=new JschDockerHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(20))
        .sslConfig(config.getSSLConfig())
        .dockerHost(config.getDockerHost())
        .build()
        ){
        ...
        }
```

### additional configuration 

| env variable                          | description                                                                                                                                                                                                                                                                                                                                   | example  |
|---------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| DOCKER_HOST                           | Daemon socket to connect to. For this project, it makes only sense to use a ssh connection of scheme `ssh://[username@]<IP or host>[:port]`                                                                                                                                                                                                   |          |
| JSCH_DOCKER_ADDITIONAL_FILE_TO_SOURCE | if for any reason additional config is needed to be able to connect to the daemon, a file can be referenced, which is sourced on the remote before connecting the daemon. <br/>Be aware, that the ssh connection is doing a direct `RemoteCommand` on the remote without a shell. This might be used to add the `docker` executable to `PATH` | ~/.zshrc |

### daemon connection variants in the ssh session

By setting flags in the builder, one can control how the connection is made.

* docker system dial-stdio (default)
* direct-streamlocal `.useSocket()` or `.useSocket("/my/path/to/docker.socket")`
* direct-tcpip `.useTcp()` or `.useTcp(8765)`
* socat `.useSocat()` or `.useSocat("/my/path/to/docker.socket")`

### authentication variants

The SSH authentication relies on the `Jsch` mechanisms.

Configuration-guidance:

- Password:
  ```java
   JschDockerHttpClient.Builder()
  ...
  .userInfo(new com.jcraft.jsch.UserInfo(){
  ...
  })
  .build();
  ```
- SSH-Agent:
  
  - *nix:
    - use java 16 and above or add [junixsocket](https://github.com/kohlschutter/junixsocket) to the classpath
    ````java
    IdentityRepository identityRepository = new AgentIdentityRepository(new SSHAgentConnector());
    new JschDockerHttpClient.Builder()
    ...
    .identityRepository(identityRepository)
    .build();
    ````
  - Windows with Pageant:
    - add dependency: put [jna-platform](https://mvnrepository.com/artifact/net.java.dev.jna/jna-platform) into the classpath
    ````java
    IdentityRepository identityRepository = new AgentIdentityRepository(new PageantConnector());
    new JschDockerHttpClient.Builder()
    ...
    .identityRepository(identityRepository)
    .build();
    ````
## testing

reuse of integrations-tests from [a docker-java](https://github.com/docker-java/docker-java) by applying patches.

Always make sure, that you have set up a Docker Host available via ssh and that the host is set in `DOCKER_HOST`
environment variable and that the ssh config to this host is setup in `~/ssh/config`. (compare to what is done in CI environment
in [setup_ssh_config.sh](.ci/setup_ssh_config.sh)). 

For example in Github Codespaces as of 06/2023, the ssh port is 2222, not 22.

example maven command: `DOCKER_HOST=ssh://junit-host mvn verify -Dit.test=JschDockerHttpClientIT`

## dockerd configurations

On the remote host, one can connect to the docker daemon in several ways:

* `docker system dial-stdio`
* `unix:///var/run/docker.sock` (default on
  linux) https://docs.docker.com/engine/reference/commandline/dockerd/#daemon-socket-option
* `npipe:////./pipe/docker_engine` (default on
  Windows) https://docs.docker.com/docker-for-windows/faqs/#how-do-i-connect-to-the-remote-docker-engine-api
* `unix:///var/run/docker.sock` (default on
  macos) https://docs.docker.com/docker-for-mac/faqs/#how-do-i-connect-to-the-remote-docker-engine-api
* tcp 2375
* tcp with TLS

## limitations

__windows__

Since forwarding socket of windows host is not supported, there is the workaround of starting socat to forward the
docker socket to a local tcp port.

Compare OpenSSH tickets:

* https://github.com/PowerShell/Win32-OpenSSH/issues/435
* https://github.com/PowerShell/openssh-portable/pull/433

## references

<a class="anchor" id="1">[1]</a> docker ssh support https://github.com/docker/cli/pull/1014
