# Compatible with ghcr.io/jamesbraid/unifi-network and lscr.io/linuxserver/unifi-network-application
ARG BASE_IMAGE=ghcr.io/jamesbraid/unifi-network:latest
FROM ${BASE_IMAGE}

COPY agent/build/libs/open-uos-agent.jar /opt/open-uos/open-uos-agent.jar

# Inject the runtime patch agent without modifying upstream container init scripts.
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/open-uos/open-uos-agent.jar"
