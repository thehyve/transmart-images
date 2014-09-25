#!/bin/bash

set -x

exec 3>&1
exec 4>&2
exec &> /var/log/first_boot.log

(
set -e
set -o pipefail

# variables
TOMCAT_USER=tomcat7
TOMCAT_GROUP=tomcat7
TOMCAT_HOME=`echo ~tomcat7`
TOMCAT_SERVICE=tomcat7
TOMCAT_LIB=/usr/share/tomcat7/lib
TOMCAT_CTX_DIR=/etc/tomcat7/Catalina/localhost
TOMCAT_WEBAPPS=/var/lib/tomcat7/webapps
TOMCAT_SERVER_XML=/etc/tomcat7/server.xml

TS_DATA=/opt/transmart-data
TS_ETL=/opt/tranSMART-ETL
DATA_INTEGRATION=/opt/data-integration
WAR_FILE=/opt/transmart.war
TABLESPACES_DIR=/var/lib/postgresql/tablespaces
POSTGRESQL_CONF=/etc/postgresql/9.3/main/postgresql.conf
MEMORY_DATABASE=${MEMORY_DATABASE:-1500}
MEMORY_TOMCAT=${MEMORY_TOMCAT:-3000} # + max 400 for permgen

# Firewall
ufw enable <<< y
ufw allow 22/tcp
ufw allow 80/tcp

cd $TS_DATA

# preliminaries
make -C env $TABLESPACES_DIR ../vars
ln -s $TS_ETL env/tranSMART-ETL
ln -s $DATA_INTEGRATION env/data-integration
echo "export TSUSER_HOME=$TOMCAT_HOME/" >> vars
source vars

# install config
make -C config install_Config.groovy install_DataSource.groovy

# first tune postgresql
AUG_PGCONF=/files$POSTGRESQL_CONF
# these limits are relatively large; few concurrent users are assumed
augtool set $AUG_PGCONF/shared_buffers `php -r "echo (int)(.25 * $MEMORY_DATABASE);"`MB
augtool set $AUG_PGCONF/work_mem `php -r "echo (int)(.1 * $MEMORY_DATABASE);"`MB
augtool set $AUG_PGCONF/maintenance_work_mem `php -r "echo (int)(.2 * $MEMORY_DATABASE);"`MB
augtool set $AUG_PGCONF/effective_cache_size `php -r "echo (int)(.3 * $MEMORY_DATABASE);"`MB
service postgresql restart #reload?

# install database
make -j3 postgres

service $TOMCAT_SERVICE stop

# R
chown -R $TOMCAT_USER:$TOMCAT_GROUP R
(
sudo -E -u $TOMCAT_USER R_FLAGS=$R_FLAGS make -C R -j3 root/bin/R
sudo -E -u $TOMCAT_USER make -C R install_packages
rm -rf R/{build,R-*}
chsh -s /bin/bash $TOMCAT_USER
TRANSMART_USER=$TOMCAT_USER make -C R install_rserve_init
update-rc.d rserve defaults 85
) &> /var/log/R_build.log &

# ETL
(
make -C samples/postgres load_clinical_GSE8581 \
  load_ref_annotation_GSE8581 \
  load_expression_GSE8581 \
  load_clinical_TCGAOV \
  load_acgh_TCGAOV
) &> /var/log/ETL.log &

# Solr
(
make -C solr solr_home
cp -R solr/lib/ext/* $TOMCAT_LIB
chown -R $TOMCAT_USER solr/solr
sudo -u $TOMCAT_USER tee $TOMCAT_CTX_DIR/solr.xml <<EOD
<?xml version="1.0" encoding="utf-8"?>
<Context docBase="$TS_DATA/solr/webapps/solr.war" crossContext="true">
  <Environment name="solr/home" type="java.lang.String" value="$TS_DATA/solr/solr" override="true"/>
</Context>
EOD
) &> /var/log/solr_install.log &

FAILED_ANY=0
wait %1 || { echo 'ERROR: R build subprocess failed'; FAILED_ANY=1; }
wait %2 || { echo 'ERROR: ETL subprocess failed'; FAILED_ANY=1; }
wait %3 || { echo 'ERROR: Solr subprocess failed'; FAILED_ANY=1; }

if [ $FAILED_ANY -ne 0 ]; then
    echo "ERROR: Some subshells failed; exiting prematurely"
	shutdown -h now
    exit 1
fi

# Update solr index
service $TOMCAT_SERVICE start
curl -f "http://localhost:8080/solr/rwg/dataimport?command=full-import"
sleep 7 #only one document to index, should be more than enough
#TODO: poll for status at http://localhost:8080/solr/rwg/dataimport
service $TOMCAT_SERVICE stop

# WAR file
sudo -u $TOMCAT_USER ln -s $WAR_FILE $TOMCAT_WEBAPPS/transmart.war

# further tomcat configuration
sed -i 's/^JAVA_OPTS=.*/JAVA_OPTS="-Djava.awt.headless=true -Xmx'$MEMORY_TOMCAT'm -XX:MaxPermSize=400m -XX:+UseConcMarkSweepGC"/' \
    /etc/default/$TOMCAT_SERVICE

# enable native libraries (APR)
# enables sendfile support
DOL='$'
php <<EOD
<?php
${DOL}file = 'file://$TOMCAT_SERVER_XML';
${DOL}doc = new DOMDocument();
${DOL}doc->preserveWhiteSpace = false;
${DOL}doc->formatOutput = true;
if (!${DOL}doc->load(${DOL}file)) {
    echo "Failed loading XML file\n";
    exit(1);
}

${DOL}el = ${DOL}doc->createElement('Listener');

${DOL}className = ${DOL}doc->createAttribute('className');
${DOL}className->value = 'org.apache.catalina.core.AprLifecycleListener';
${DOL}el->appendChild(${DOL}className);

${DOL}sslEngine = ${DOL}doc->createAttribute('SSLEngine');
${DOL}sslEngine->value = 'off';
${DOL}el->appendChild(${DOL}sslEngine);

${DOL}doc->documentElement->insertBefore(${DOL}el,
        ${DOL}doc->documentElement->firstChild);

if (${DOL}doc->save(${DOL}file)) {
    echo "Written ${DOL}file\n";
} else {
	echo "Failed to write ${DOL}file\n";
	exit(1);
}
EOD

mkdir /var/tmp/jobs
chown $TOMCAT_USER:$TOMCAT_GROUP /var/tmp/jobs

# Apache
a2enmod proxy proxy_http
a2dissite 000-default
cat > /etc/apache2/sites-available/000-transmart.conf <<EOD
<VirtualHost *:80>
  ServerAdmin admin@thehyve.nl

  ProxyPass        /transmart http://localhost:8080/transmart
  ProxyPassReverse /transmart http://localhost:8080/transmart
  ProxyPreserveHost On

  ErrorLog /var/log/apache2/transmart_error.log
  LogLevel warn
  CustomLog /var/log/apache2/transmart_access.log combined
</VirtualHost>
EOD
a2ensite 000-transmart

# if you want to have the image operational instead of shutting down
#service rserve start
#service tomcat7 start
#service apache2 reload
# and remove the shutdown command later
)

# save the exit status of the main subshell
MAIN_EXIT=$?

if [ $MAIN_EXIT -eq 0 ]; then
    touch /var/log/first_boot_succeeded
else
    touch /var/log/first_boot_failed
fi

# mount and copy logs to the shared logs directory
mkdir /tmp/logs_shared
mount -t 9p -o trans=virtio,version=9p2000.L logsshare /tmp/logs_shared

if [ -f /var/log/first_boot_succeeded ]; then
    cp /var/log/first_boot_succeeded /tmp/logs_shared
fi
if [ -f /var/log/first_boot_failed ]; then
    cp /var/log/first_boot_failed /tmp/logs_shared
fi

cp /var/log/{R_build.log,ETL.log,solr_install.log} /tmp/logs_shared

#restore original stdout/stderr (and thereby closing the log file)
exec 1>&3
exec 2>&4

cp /var/log/first_boot.log /tmp/logs_shared

umount /tmp/logs_shared


# that's it!
shutdown -h now
