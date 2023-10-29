# Use a base image with a Java runtime
FROM openjdk:17-jdk-slim

# Set the working directory inside the container
WORKDIR /app

# Copy the JAR file from the local file system to the container's working directory
COPY subscriber/target/scala-2.13/subscriber.jar /app/subscriber.jar

# Command to run the JAR file
CMD ["java", "-jar", "subscriber.jar"]