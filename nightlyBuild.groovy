#!/usr/bin/groovy
import groovy.xml.XmlUtil

@Grab(group='org.libvirt', module='libvirt', version='0.4.7')
@Grab(group='com.sun.jna', module='jna', version='3.0.9')
import org.libvirt.Connect
import org.libvirt.Domain
import org.libvirt.LibvirtException

def fixedSettings = [
        ubuntuImage:        'http://cloud-images.ubuntu.com/trusty/current/trusty-server-cloudimg-arm64-disk1.img',
        kernel:             'http://cloud-images.ubuntu.com/trusty/current/unpacked/trusty-server-cloudimg-arm64-vmlinuz-generic',
        imageSize:          '10G',
        kettleVersion:      '4.4.0',
        firstBootScript:    'firstBoot.sh',
        aptMirror:          'http://nl.archive.ubuntu.com/ubuntu/',
        extraSources_1:     'deb http://apt.postgresql.org/pub/repos/apt/ sid-pgdg main;ACCC4CF8;postgresql.list',
        packages:           'postgresql-9.3,make,rsync,libcairo-dev,php5-cli,php5-json,curl,openjdk-7-jdk,gfortran,g++,acpid,tomcat7,libtcnative-1,apache2,augeas-tools',
        password:           'ubuntu',
        buildFinalImage:    'yes',
        compressFinalImage: 'yes',
        memory:             '5000',
        env_R_FLAGS:        '-O2',
        env_MEMORY_TOMCAT:  '2800',
        env_MEMORY_DATABASE:'1600',
]

def loader = new GroovyClassLoader(getClass().classLoader)
def utils = loader.parseClass(new File("inc/Utils.groovy"))
utils.importAll(binding)

def input = new Properties()
new File(args[0]).withReader {
    input.load it
}
input = input.withDefault { String key ->
    throw new IllegalArgumentException("Input file is missing key $key")
}

def settings = new Properties()

// the directory where the images and log files will be put
def date = new Date().format('yyyy-MM-dd')
File workDirectory = new File(input.outputDirectory, date)
if (workDirectory.exists()) {
    throw new IllegalStateException("The directory $workDirectory already exists! Aborting")
}
if (!workDirectory.mkdirs()) {
    throw new IOException("Could not create directory $workDirectory")
}

def finalImageFile = new File(workDirectory, "${input.instanceId}.qcow2")

settings.tsDataCommit     = findHead input.dataRepository, input.dataBranch
settings.tsETLCommit      = findHead input.etlRepository, input.etlBranch

settings.tsAppKey         = input.tsAppKey
settings.tsAppBuild       = findLatestBuildNumber(input.bamboo, input.tsAppKey)
settings.bambooURL        = input.bamboo

settings.hostname         = settings.instanceId = input.instanceId
settings.fqdn             = "${input.instanceId}.${input.domain}" as String

settings.mainImageOverlay = "${input.instanceId}-intermediate.qcow2" as String
settings.auxiliaryImage   = "${input.instanceId}-auxiliary.iso" as String
settings.finalImage       = finalImageFile.path

settings.domainXml        = new File(workDirectory, "${input.instanceId}.xml").path

settings.authorized_keys  = new File('keys').listFiles().findAll {
    it.isFile() && it.name ==~ /.*\.pub$/
}.join ','

settings << fixedSettings

File settingsFile = new File(workDirectory, 'settings.properties')
settingsFile.withOutputStream {
    Properties properties = new Properties()
    properties << settings
    properties.store(it, 'UTF-8')
}

/* Run buildImage.groovy */
File buildImageLog = new File(workDirectory, 'buildImage.log')

buildImageLog.withOutputStream { logStream ->
    // Run buildImage.groovy
    def result = exec command: [ 'groovy', 'buildImage.groovy', settingsFile.path ],
            stdoutRedirect: logStream,
            stderrRedirect: logStream,
            throwOnFailure: false,
            timeout: 3600000

    // Copy log files to the  the workDirectory
    new File('logs', input.instanceId).eachFile { File logFile ->
        logFile.withInputStream { is ->
            new File(workDirectory, logFile.name) << is
        }
    }

    if (result.failed) {
        throw new RuntimeException("The buildImage script has failed. See $buildImageLog")
    }
}


/*
 * Create running qcow2 image backed by "final image" */

def runningImageFile = new File(input.runningImageDirectory,
        "${input.instanceId}-${date}.qcow2")

exec command: ['qemu-img', 'create', '-f', 'qcow2', '-b', settings.finalImage,
        runningImageFile ]

/*
 * Retrieve and stop the old domain */

 //def libvirtConnection = new Connect("qemu+ssh://root@dev2/system", true);
log "Connecting to the libvirt daemon"
def libvirtConnection = new Connect("qemu:///system", false)

Domain domain
try {
    log "Retrieving domain ${input.instanceId}"
    domain = libvirtConnection.domainLookupByName(input.instanceId)
    log "Found domain ${input.instanceId}"
} catch (LibvirtException lbe) {
    if (lbe.error.code != org.libvirt.Error.ErrorNumber.VIR_ERR_NO_DOMAIN) {
        log "Domain not found"
        throw lbe
    }
}

String domainXml
File oldRunningImageFile
if (domain && 'yes' == input.preserveDomainXml) {
    domainXml = domain.getXMLDesc(0)
} else {
    domainXml = new File(workDirectory, "${input.instanceId}.xml").getText('UTF-8')
}

def domainXmlRoot = new XmlSlurper().parseText(domainXml)
if (domain) {
    oldRunningImageFile = new File(domainXmlRoot.devices.disk.source.@file.text())
    log "Image of current machine: $oldRunningImageFile"
}
domainXmlRoot.devices.disk.source.replaceNode {
    source file: runningImageFile.absolutePath
}
domainXml = XmlUtil.serialize(domainXmlRoot)

if (domain && domain.isActive()) {
    log "Destroying (stopping) old domain"
    domain.destroy()
    log "Old domain destroyed"
}

/*
 * Change the old domain or create a new one and start it */
log "Creating new libvirt domain"
domain = libvirtConnection.domainDefineXML domainXml
domain.autostart = true
domain.create()
log "libvirt domain created"

/*
 * Delete disks for the old domain (running and "final")
 * to reclaim space */

if (oldRunningImageFile && oldRunningImageFile.exists() &&
        oldRunningImageFile.canonicalFile != runningImageFile.canonicalFile) {
    log "Deleting $oldRunningImageFile"
    if (!oldRunningImageFile.delete()) {
        throw new IOException("Could not delete $oldRunningImageFile")
    }
}
workDirectory.parentFile.eachFile { dir ->
    if (!dir.isDirectory() || !(dir.name ==~ /\d{4}-\d{2}-\d{2}/)
            || dir.name == date) {
        return
    }
    dir.eachFileMatch ~/.+\.qcow2/, { file ->
        if (!file.delete()) {
            throw new IOException("Could not delete $file")
        } else {
            log "Deleted $file"
        }
    }
}


/*************
 * Functions *
 *************/
String findHead(String repository, String branch) {
    log "Determining commit for branch $branch, repository $repository"

    def result = exec(
            command: [ 'git', 'ls-remote', '--exit-code', repository, branch ],
            throwOnFailure: false,
            suppressStdout: true)

    if (result.failed) {
        throw new RuntimeException("Could not find commit for branch $branch of repository $repository")
    }

    new String(result.stdoutData[0..31] as byte[], 'UTF-8')
}

String findLatestBuildNumber(String server, String planKey) {
    String uri = "$server/rest/api/latest/result/$planKey/latest/"
    log "Finding out the latest build number from $uri"

    def slurper = new XmlSlurper()
    def result = slurper.parse(uri)

    result.@number.text()
}
