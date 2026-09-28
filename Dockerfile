# scala-pds: an AT Protocol PDS. The image runs a single assembled jar; the
# account interface is committed, so no Node toolchain is needed here.
ARG JDK_IMAGE="eclipse-temurin:21-jdk-noble"
ARG RUNNER_IMAGE="eclipse-temurin:21-jre-noble"
ARG SBT_VERSION="1.10.7"

FROM ${JDK_IMAGE} AS builder
ARG SBT_VERSION

RUN apt-get update -y \
  && apt-get install -y --no-install-recommends bash ca-certificates curl \
  && rm -rf /var/lib/apt/lists/* \
  && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
     -o /tmp/sbt.tgz \
  && tar -xzf /tmp/sbt.tgz -C /opt \
  && rm /tmp/sbt.tgz

ENV PATH="/opt/sbt/bin:${PATH}"
WORKDIR /app

# Resolve dependencies against the build definition alone so the layer caches.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt -batch update

COPY src src
RUN sbt -batch assembly \
  && mv target/scala-*/scala-pds.jar /app/scala-pds.jar

FROM ${RUNNER_IMAGE} AS runner

RUN apt-get update -y \
  && apt-get install -y --no-install-recommends ca-certificates curl \
  && rm -rf /var/lib/apt/lists/* \
  && useradd --system --create-home --home-dir /home/pds --shell /usr/sbin/nologin pds

WORKDIR /app
COPY --from=builder /app/scala-pds.jar /app/scala-pds.jar
RUN mkdir -p /data && chown -R pds:pds /data /app

USER pds
ENV PDS_HOST="0.0.0.0" \
    PDS_PORT="3000" \
    PDS_SQLITE_PATH="/data/scala-pds.sqlite3" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

VOLUME ["/data"]
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PDS_PORT}/xrpc/_health" >/dev/null

# Migrations run under a lock before the listener binds.
ENTRYPOINT ["java", "-jar", "/app/scala-pds.jar"]
