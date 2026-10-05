FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data && chown 10001:10001 /app/data
COPY target/web3-vaadin-monitor-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
