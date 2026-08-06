FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/gateproxy-1.0.0.jar /app/gateproxy.jar
ENV DEMO_MODE=true
ENV BIND_HOST=0.0.0.0
EXPOSE 8080
CMD ["sh", "-c", "java -jar /app/gateproxy.jar"]
