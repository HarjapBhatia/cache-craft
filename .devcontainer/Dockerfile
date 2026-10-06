FROM grafana/k6:latest AS k6

FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# k6 is copied from Grafana's official image. Maven is supplied by the Java
# development-container image; Docker is supplied by the dev-container feature.
COPY --from=k6 /usr/bin/k6 /usr/local/bin/k6
