# --------------- 第一阶段：构建项目（官方Maven镜像，自动打包，无需手动下载）---------------
FROM maven:3.8.6-openjdk-8 AS builder
WORKDIR /app
COPY . .
# 打包项目（跳过测试）
RUN mvn clean package -DskipTests

# --------------- 第二阶段：运行项目（轻量级JRE，体积小）---------------
FROM openjdk:8-jre-slim
WORKDIR /app
# 从构建阶段复制打包好的jar包
COPY --from=builder /app/target/*.jar app.jar
# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
