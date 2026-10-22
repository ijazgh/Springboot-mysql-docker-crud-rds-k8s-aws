FROM eclipse-temurin:26-jre
WORKDIR /app
COPY certs/rds-truststore.p12 /app/certs/rds-truststore.p12
COPY target/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
