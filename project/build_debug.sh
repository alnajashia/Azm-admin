#!/bin/bash
# Build Azm Admin in this sandbox, reusing the Azm project's toolchain and
# loopback TLS relay (see the Azm project's build_apk.sh for why the relay
# exists: the sandbox egress drops Java TLS without ALPN http/1.1).
set -e
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/workspace/toolchain/jdk-21
export GRADLE_USER_HOME=$HOME/workspace/tmp_build/gradle_home
export ANDROID_HOME=$HOME/workspace/toolchain/android-sdk
export TMPDIR=$HOME/workspace/tmp_build
TS=$HOME/workspace/tmp_build/mitm/truststore.jks
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djavax.net.ssl.trustStore=$TS -Djavax.net.ssl.trustStorePassword=changeit"
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
if ! python3 -c "import socket; s=socket.create_connection(('127.0.0.1',18080),2); s.close()" 2>/dev/null; then
  nohup python3 $HOME/workspace/tmp_build/mitm_relay.py >> $HOME/workspace/tmp_build/mitm_stdout.log 2>&1 &
  sleep 2
fi
exec ./gradlew "$@"
