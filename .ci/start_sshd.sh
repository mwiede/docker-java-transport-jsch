#!/usr/bin/env bash

set -exu

mkdir -p ${HOME}/custom_ssh
ssh-keygen -f ${HOME}/custom_ssh/ssh_host_rsa_key -N '' -t rsa
ssh-keygen -f ${HOME}/custom_ssh/ssh_host_dsa_key -N '' -t dsa

cat << EOF > ${HOME}/custom_ssh/sshd_config
Port 2222
HostKey ${HOME}/custom_ssh/ssh_host_rsa_key
HostKey ${HOME}/custom_ssh/ssh_host_dsa_key
AuthorizedKeysFile  .ssh/authorized_keys
ChallengeResponseAuthentication no
UsePAM no
Subsystem   sftp    /usr/lib/ssh/sftp-server
PidFile ${HOME}/custom_ssh/sshd.pid
EOF

/usr/sbin/sshd -f ${HOME}/custom_ssh/sshd_config
echo "----- Process ID : ${HOME}/custom_ssh/sshd.pid -------"