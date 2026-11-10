ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-noble
FROM ${JAVA_RUNTIME_IMAGE}
USER 0:0
WORKDIR /app
RUN install -d -o 10001 -g 10001 /data/knowledge
COPY auto-care-server/target/auto-care-server-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
