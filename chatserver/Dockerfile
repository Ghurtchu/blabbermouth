# Use an official OpenJDK runtime as a parent image
FROM hseeberger/scala-sbt:11.0.12_1.5.5_2.13.6

# Set the working directory to /app
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

# Build the Scala project
RUN sbt "project chatserver" clean assembly

# Run application when the container launches
CMD ["sh", "-c", "java -jar chatserver/target/scala-2.13/chatserver.jar"]
