import groovy.transform.Canonical
import groovy.xml.MarkupBuilder
import org.yaml.snakeyaml.DumperOptions
@Grab(group='org.yaml', module='snakeyaml', version='1.13')
import org.yaml.snakeyaml.Yaml

def loader = new GroovyClassLoader(getClass().classLoader)
def utils = loader.parseClass(new File("inc/Utils.groovy"))
utils.importAll(binding)

if (args.length != 1) {
    System.err.println 'Syntax: <settings file>'
    System.exit 1
}

def settingsFile = new File(args[0])
if (!settingsFile.isFile()) {
    System.err.println "No such settings file $settingsFile"
    System.exit 1
}

settings = new Properties()
settingsFile.withInputStream {
    settings.load new InputStreamReader(it, 'UTF-8')
}
settings = settings.withDefault { String key ->
    throw new IllegalArgumentException("Settings file is missing key '$key'")
}

cacheDir = new File('cache')
deleteOld cacheDir, ~/transmart-(.+)-\d+\.war/
deleteOld cacheDir, ~/thehyve-(.+)-[a-z0-9]{32}\.tar\.gz/

def originalImage = downloadFile(new URL(settings.ubuntuImage), new File('.'))
// for performance, we could uncompress the image:
// qemu-img convert -O qcow2 $originalImage $uncompressImage
def kernel = downloadFile(new URL(settings.kernel), new File('.'))

def overlay = new File(settings.mainImageOverlay)
if (overlay.exists()) {
    log "File $overlay will be replaced"
}
// qemu-img create -f qcow2 -b saucy-server-cloudimg-amd64-disk1.img disk.img 10G
exec command: ['qemu-img', 'create', '-f', 'qcow2', '-b', originalImage, overlay, settings.imageSize]

class AuxiliaryImage {
    File image
    File cacheDir
    MetaData metadata
    UserData userdata
    private Script script

    private File scratchDir
    private File opt
    private File noCloud

    final static String FIRST_BOOT_SCRIPT = '/opt/first_boot'
    final static String FIRST_BOOT_ENV = '/opt/first_boot_env'

    AuxiliaryImage(Script script, File image, File cacheDir, MetaData metaData, UserData userData) {
        if (image.exists()) {
            script.log "$image will be overwritten"
        }
        this.image = image
        this.cacheDir = cacheDir
        this.script = script
        this.metadata = metaData
        this.userdata = userData
    }

    void build() {
        createScratchFilesystem()
        createOpt()
        createNoCloud()
        copySettings() //for reference
        writeEnvFile() //environment variables for firstBoot.sh
        writeUpdatesScript() //run right after copying stuff from update/
        writeFirstBootScript()
        writeMetaData()
        writeUserData()
        downloadTransmartWAR()
        downloadTransmartData()
        downloadTransmartETL()
        downloadKettle()
        buildIsoImage()

        new AntBuilder().delete dir: scratchDir
    }

    private void createScratchFilesystem() {
        scratchDir = File.createTempDir('buildImage-', '')
        script.log "Created scratch directory $scratchDir"
    }

    private void createOpt() {
        opt = new File(scratchDir, 'updates/opt')
        script.log "Create /opt directory at $opt"
        if (!opt.mkdirs()) {
            throw new RuntimeException('Failed to create directory')
        }
    }

    private void createNoCloud() {
        noCloud = new File(scratchDir, 'updates/var/lib/cloud/seed/nocloud')
        script.log "Create nocloud dir at $noCloud"
        //XXX: mkdir() failing here
        new AntBuilder().mkdir dir: noCloud
    }

    private void copySettings() {
        File destination = new File(opt, 'settings.properties')
        script.log "Saving settings at $destination"
        destination.withOutputStream {
            Properties properties = new Properties()
            properties << script.settings
            properties.store(it, 'Generated from buildImage.groovy')
        }
    }

    private void writeEnvFile() {
        script.log "Write first boot environment file"
        File destination = new File(opt, 'first_boot_env')
        script.writeEnvFile script.settings, destination
    }

    private void writeUpdatesScript() {
        script.log "Copy updates.script"
        def destination = new File(scratchDir, 'updates.script')
        copyFile new File('updates.script'), destination
        destination.setExecutable(true, false)
    }

    private void writeFirstBootScript() {
        def scriptFile = new File(opt, 'first_boot')
        copyFile new File(firstBootScript), scriptFile
        scriptFile.setExecutable(true, false)
    }

    private void writeMetaData() {
        def file = new File(noCloud, 'meta-data')
        script.log "Write metadata to $file"
        metadata.writeTo file
    }

    private void writeUserData() {
        def file = new File(noCloud, 'user-data')
        script.log "Write userdata to $file"
        userdata.writeTo file
    }

    private void downloadTransmartWAR() {
        File cacheDestination = new File(cacheDir, "transmart-$tsAppKey-${tsAppBuild}.war")
        File destination = new File(opt, 'transmart.war')
        URL url = new URL("$bambooURL/browse/$tsAppKey-$tsAppBuild/artifact/shared/transmartApp-WAR/transmart.war")
        script.downloadFile url, null, cacheDestination
        copyFile cacheDestination, destination
    }

    private void downloadTransmartData() {
        File destination = new File(scratchDir, 'transmart-data.tar.gz')
        downloadGitHubCommit destination, 'thehyve', 'transmart-data', tsDataCommit
    }

    private void downloadTransmartETL() {
        File destination = new File(scratchDir, 'tranSMART-ETL.tar.gz')
        downloadGitHubCommit destination, 'thehyve', 'tranSMART-ETL', tsETLCommit
    }

    private void downloadKettle() {
        def kettlePackage = script.downloadFile(new URL("http://downloads.sourceforge.net/project/pentaho/" +
                "Data%20Integration/${kettleVersion}-stable/pdi-ce-${kettleVersion}-stable.tar.gz"), cacheDir)
        copyFile kettlePackage, new File(scratchDir, 'pdi-ce.tar.gz')
    }

    private void buildIsoImage() {
        script.exec command: ['genisoimage', '-rock', '-quiet', '--output', image.path, scratchDir]
    }

    private void downloadGitHubCommit(File destination, String user, String repository, String commit) {
        File cacheDestination = new File(cacheDir, "$user-$repository-${commit}.tar.gz")
        URL url = new URL("https://github.com/$user/$repository/archive/${commit}.tar.gz")
        script.log("About to download repository snapshot at $url to $cacheDestination")
        script.downloadFile url, null, cacheDestination
        copyFile cacheDestination, destination
    }

    private void copyFile(File source, File destination) {
        script.log "Copy $source to $destination"
        if (destination.exists()) {
            throw new RuntimeException("Destination $destination already exists")
        }
        source.withInputStream {
            destination << it
        }
    }

    def propertyMissing(String prop) {
        /* take missing properties from the settings file */
        script.settings."$prop"
    }
}

@Canonical
class MetaData {
    String hostname
    String instanceId

    void writeTo(File file) {
        file.withWriter 'UTF-8', {
            it << YAML.dump([hostname: hostname, 'instance-id': instanceId])
        }
    }
}

@Canonical
class UserData {
    Boolean apt_update = true
    Boolean apt_upgrade = false
    String apt_mirror
    Boolean manage_etc_hosts = true
    String hostname
    String fqdn
    List<String> packages = []
    String password
    Boolean ssh_pwauth = false
    Map<String, String> ssh_keys
    List<String> ssh_authorized_keys = []
    List<List<String>> runcmd = []
    Map<String, String> chpasswd = [ expire: false ]
    List<Map<String, String>> apt_sources

    void writeTo(File file) {
        file.withWriter 'UTF-8', {
            it << "#cloud-config\n"
            it << YAML.dump(properties.findAll { it.key != 'class' })
        }
    }
}

def metaData = new MetaData(hostname: settings.hostname, instanceId: settings.instanceId)
def userData = new UserData(
        packages: settings.packages.split(','),
        password: settings.password,
        ssh_keys: [
                rsa_private: fetchKey(settings.instanceId, 'rsa', true).text,
                rsa_public: fetchKey(settings.instanceId, 'rsa', false).text,
                dsa_private: fetchKey(settings.instanceId, 'dsa', true).text,
                dsa_public: fetchKey(settings.instanceId, 'dsa', false).text,
        ],
        ssh_authorized_keys: settings.authorized_keys.split(',').collect { new File(it).text },
        runcmd: [ [ '/bin/bash', '-c',
                ". ${AuxiliaryImage.FIRST_BOOT_ENV}; ${AuxiliaryImage.FIRST_BOOT_SCRIPT}" as String ] ],
        apt_mirror: settings.aptMirror,
        hostname: settings.hostname,
        fqdn: settings.fqdn,
        apt_sources: settings.findAll { it.key ==~ /^extraSources_\d+$/ }.collect {
            def values = it.value.split(';');
            [ source: values[0], keyid: values[1], filename: values[2] ]
        },
)

def auxiliaryImageFile = new File(settings.auxiliaryImage)
def auxiliaryImage = new AuxiliaryImage(this, auxiliaryImageFile, cacheDir, metaData, userData)
auxiliaryImage.build()

def logsDirectory = new File('logs', settings.instanceId)
ensureCleanDirectory(logsDirectory)

def firstRunCommand = [
	'qemu-system-x86_64',
    '-enable-kvm',
    '-kernel', "$kernel",
    '-drive', "file=$settings.mainImageOverlay,if=virtio",
    '-drive', "file=$settings.auxiliaryImage,if=virtio",
    '-append', 'root=/dev/vda1 ro init=/usr/lib/cloud-init/uncloud-init ds=nocloud xupdate=vdb:mnt console=ttyS0',
    '-cpu', 'host',
    '-smp', '3',
    '-m', settings.memory,
    '-net', 'nic,model=virtio',
    '-net', 'user,hostfwd=tcp::5555-:22,hostfwd=tcp::1180-:80',
    '-fsdev', "local,security_model=none,id=fsdev0,path=$logsDirectory",
    '-device', 'virtio-9p-pci,id=fs0,fsdev=fsdev0,mount_tag=logsshare',
    '-nographic',
]

if ('yes' != settings.buildFinalImage) {
	log "Finished!"

	println """Do the initial run with:
${firstRunCommand.collect { it.contains(' ') ? "'$it'" : it }.join(' ')}

If you want to be able to run the image in virtual machines that expose fewer
processor instructions, consider changing the -cpu flag.

When the machine shuts down, you can build the final image with:
qemu-img convert -c -O qcow2 $settings.mainImageOverlay <final image.img>
"""
	System.exit 0
}

exec command: firstRunCommand, timeout: 3600000

if (!new File(logsDirectory, 'first_boot_succeeded').exists()) {
    throw new RuntimeException("Could not find the file first_boot_succeeded in $logsDirectory")
}

exec command: [ 'qemu-img', 'convert',
	*(settings.compressFinalImage == 'yes' ? [ '-c' ] : []),
	'-O', 'qcow2', settings.mainImageOverlay, settings.finalImage ],
	timeout: 300000

def domainXmlFile = new File(settings.domainXml)
if (domainXmlFile.exists()) {
    log "Will overwrite $domainXmlFile"
}
domainXmlFile.withOutputStream {
    def writer = new OutputStreamWriter(it, 'UTF-8')
    def xml = new MarkupBuilder(writer)
    xml.domain(type: 'kvm') {
        name settings.instanceId
        uuid(UUID.randomUUID() as String)
        memory unit: 'MiB', settings.memory
        currentMemory unit: 'MiB', settings.memory
        vcpu placement: 'static', '3'
        cpu mode: 'host-passthrough'
        os {
            type arch: 'x86_64', machine: 'pc', 'hvm'
        }
        features {
            acpi()
        }
        clock offset: 'utc'
        on_poweroff 'destroy'
        on_reboot 'restart'
        on_crash 'restart'
        devices {
            emulator '/usr/bin/qemu-system-x86_64'
            disk type: 'file', device: 'disk', {
                source file: (new File(settings.finalImage).absolutePath)
                driver name:  'qemu', type: 'qcow2'
                target dev: 'vda', bus: 'virtio'
            }
            'interface'(type: 'network') {
                source network: 'default'
                model type: 'virtio'
            }
            graphics type: 'vnc', port: '-1'
        }
    }
}
log "domain.xml for libvirt written to $domainXmlFile"

auxiliaryImageFile.delete()
log "Deleted $auxiliaryImageFile"

overlay.delete()
log "Deleted $overlay"

log "Finished! You can now import $domainXmlFile into libvirt"

class YAML {
    static yaml
    static {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        yaml = new Yaml(options)
    }

    static String dump(Object o) {
        yaml.dump(o)
    }
}

/*
 * Functions
 */
File fetchKey(String instanceId, String type, Boolean privateKey) {
    File dir = new File('keys', instanceId)
    if (!dir.exists()) {
        if (!dir.mkdirs()) {
            throw new IOException("Could not create $dir")
        }
    }
    File key = new File(dir, "ssh_host_${type}_key${privateKey ? '' : '.pub'}")

    if (!key.isFile()) {
        if (privateKey == false) {
            throw new IllegalStateException("We only generate keys when asked a private key")
        }
        log "Generating key par $key and ${key}.pub"
        exec command: [ 'ssh-keygen', '-t', type, '-h', '-q', '-N', '', '-f', key as String ]
    }
    key
}

void writeEnvFile(settings, File target) {
    String res = settings.findAll { it.key ==~ /env_.+/ }.collect {
        "export ${it.key.substring('env_'.size())}='${it.value}'".toString()
    }.join '\n'

    target << res
}

//vim: set et ts=4 sts=4 sw=4 et
