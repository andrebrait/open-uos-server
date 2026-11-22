ARG BASE_IMAGE=ghcr.io/lemker/unifi-os-server:latest
FROM ${BASE_IMAGE}

COPY agent/build/libs/open-uos-agent.jar /opt/open-uos/open-uos-agent.jar
COPY docker/uosserver/unifi-agent.conf /etc/systemd/system/unifi.service.d/99-open-uos-agent.conf
