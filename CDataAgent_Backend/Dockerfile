# ─── 构建阶段 ───
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
# 先下载依赖（利用 Docker 缓存层）
RUN mvn dependency:resolve -B -q
COPY src ./src
RUN mvn clean package -DskipTests -B -q

# ─── 运行阶段 ───
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 9999
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
