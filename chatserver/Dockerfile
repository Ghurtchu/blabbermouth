# Use a base image with a Java runtime
FROM openjdk:17-jdk-slim

# Set the working directory inside the container
WORKDIR /app

# Copy the JAR file from the local file system to the container's working directory
COPY chatserver/target/scala-2.13/chatserver.jar /app/chatserver.jar

# Command to run the JAR file
CMD ["java", "-jar", "chatserver.jar"]