transmart-images
----------------

Provides a set of scripts for building and deploying
images of [tranSMART][transmart], especially for continuous integration
purposes. The following software is used:

* [transmartApp][ts-app] – the main tranSMART Grails application. The
  application is not built by these scripts; instead, a WAR file with the built
  application (including all the required plugins, e.g. [RModules][rmod]) is
  downloaded from a CI server (The Hyve's [bamboo instance][bamboo]) instance is
  currently used).

* [transmart-data][ts-data] – for setting up the database, loading example data,
  compiling R and installing its required packages and configuring Solr.

* [tranSMART-ETL][ts-etl] – the actual code that loads the data; invoked by
  `transmart-data`.

Only The Hyve's master branch of tranSMART is currently supported.

Requirements include groovy, qemu, libvirt (daemon and development libraries)
and KVM support.

The most important scripts included are:

* `buildImage.groovy` – creates a compressed qcow2 image with tranSMART and its
  dependencies installed. It is passed a configuration file. An example is
  provided; see `settings_master.properties`. It downloads an image of Ubuntu
  server, a tranSMART WAR file, a snapshot of `transmart-data` and
  `tranSMART-ETL` and Kettle, sets up the machine to be configured on its first
  boot (via [cloud-init][clinit], which at the end invokes the provided
  `firstBoot.sh`) and runs the machine a first time. When `firstBoot.sh`
  finishes, it shuts down the virtual machine and script continues, creating the
  final image from the disk's state after the VM shutdown.
  It also writes a domain XML file for libvirt.
* `firstBoot.sh` – prepares the machine on the first boot. Does the actual
  installation of tranSMART, Solr and Rserve as well as data loading on the VM's
  first run. The log files are copied to the host at the end, together with a
  marker file `first_boot_succeeded` if everything went right.
* `nightlyBuild.groovy` – wrapper for `buildImage.groovy` that takes care of
  managing daily builds. It is a passed a configuration file. See the example
  `test_nightly_settings.properties`. It takes care of keeping a directory
  structure with dated folders for each build where the logs and the final image
  is kept. It also restarts the libvirt domain so it is run with the new
  image. The final image generated by `buildImage.groovy` is left intact; the
  domain is run from a new qcow2 image that is backed by the final image. It
  also deletes old images in order to save space.
* `cron-example` is an example bash script that invokes `nightlyBuild.groovy`.

##FAQ

_How do I run the image under VirtualBox?_

VirtualBox does not support QCOW2 images. You can convert them with `qemu-img`.
E.g.:

    qemu-img convert -O vdi converted.vdi ts-master-ci.qcow2

  [transmart]: http://transmartfoundation.org/
  [ts-app]: https://github.com/thehyve/transmartApp
  [ts-etl]: https://github.com/thehyve/tranSMART-ETL
  [rmod]: https://github.com/thehyve/Rmodules
  [bamboo]: https://ci.ctmmtrait.nl/
  [ts-data]: https://github.com/thehyve/transmart-data
  [clinit]: https://help.ubuntu.com/community/CloudInit
