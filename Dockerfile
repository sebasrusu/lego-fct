FROM tomcat:10.1
# copia o war gerado pelo Maven (nome observado: cc2526-1.0.war)
COPY target/cc2526-1.0.war /usr/local/tomcat/webapps/ROOT.war