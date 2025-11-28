#!/usr/bin/env bash

set -exu

mkdir -p ~/.ssh
cd ~/.ssh
ssh-keygen -q -t rsa -N "" -f jsch
cat jsch.pub >> authorized_keys

cat <<EOT >> config

Host junit-host
	HostName localhost
	IdentityFile ~/.ssh/jsch
	PreferredAuthentications publickey
EOT

chmod go-w $HOME $HOME/.ssh
chmod 600 $HOME/.ssh/authorized_keys

ssh -o StrictHostKeyChecking=accept-new -q junit-host exit

