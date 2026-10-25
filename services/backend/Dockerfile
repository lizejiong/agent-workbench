FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY services/backend/target/rag-backend-0.1.0-SNAPSHOT.jar /app/backend.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/backend.jar"]
