FROM apache/rocketmq:5.3.3
USER root
COPY --chown=3000:3000 bin/ /opt/rocketmq/bin/
COPY --chown=3000:3000 conf/ /opt/rocketmq/conf/
COPY --chown=3000:3000 lib/ /opt/rocketmq/lib/
ENV ROCKETMQ_HOME=/opt/rocketmq
ENV ROCKETMQ_VERSION=5.5.1
WORKDIR /opt/rocketmq/bin
ENTRYPOINT []
USER rocketmq
