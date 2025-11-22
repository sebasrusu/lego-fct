FROM tomcat:10-jdk21-openjdk

WORKDIR /usr/local/tomcat

COPY ../target/cc2526-1.0.war /usr/local/tomcat/webapps/app.war

EXPOSE 8080

