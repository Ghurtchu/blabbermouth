# Use a base image with a Java runtime
# FROM openjdk:17-jdk-slim

# Set the working directory inside the container
# WORKDIR /app

# Copy the JAR file from the local file system to the container's working directory
# COPY chatserver/target/scala-2.13/chatserver.jar /app/chatserver.jar

# Command to run the JAR file
# CMD ["java", "-jar", "chatserver.jar"]

# Use an official OpenJDK runtime as a parent image
# FROM openjdk:11-jre-slim

# Set the working directory to /app
# WORKDIR /app

# COPY . /app

# Build the Scala project
#RUN cd ../
#RUN sbt "; project chatserver; clean; show assembly"

#RUN ls target/scala-2.13/

#RUN cp target/scala-2.13/chatserver.jar /app/

#WORKDIR /app

# Specify the command to run on container startup
#CMD ["java", "-jar", "chatserver.jar"]

# Use an official OpenJDK runtime as a parent image
FROM openjdk:17-jdk-slim

RUN pwd

# Set the working directory to /app
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

RUN apt-get update && \
    apt-get install -y curl && \
    curl -L https://github.com/sbt/sbt/releases/download/v1.5.5/sbt-1.5.5.tgz | tar -xzvf - -C /usr/local && \
    ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

# Build the Scala project
RUN sbt "project chatserver" clean assembly

# Run application when the container launches
CMD ["sh", "-c", "java -jar chatserver/target/scala-2.13/chatserver.jar"]
