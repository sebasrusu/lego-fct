FROM tomcat:10.1
# copia o war gerado pelo Maven (nome observado: cc2526-1.0.war)
COPY webapp/target/*.war /usr/local/tomcat/webapps/rest.war
