import java.util.regex.Matcher
import java.util.regex.Pattern

import static groovy.io.FileType.FILES

class Utils {

    /**
     * Copies all the methods to the passed binding.
     * @param binding
     */
    static void importAll(binding) {
        def methods = [ 'downloadFile', 'log', 'exec',
                'ensureCleanDirectory', 'deleteOld' ]
        methods.each { methodName ->
            binding."${methodName}" = this.&"${methodName}"
        }
    }

    /**
    * Downloads a file, gives back the resulting filename.
    *
    * Skips the download if the target file already exists.
    *
    * @param url The file to download
    * @param targetFile The file that should be created with the remote contents
    * @return The target file
    * @throws NoSuchElementException if the target file cannot be determined
    */
    static File downloadFile(URL url, File targetDir, File targetFile = null) {
        if (targetFile == null) {
            targetFile = new File(targetDir, url.path.split('/')[-1])
        }
        log "Download $url into $targetFile"
        if (targetFile.exists()) {
            if (!targetFile.isFile()) {
                throw new IllegalArgumentException("Target file $targetFile is a directory")
            }
            log "File already exists; skipping download"
            return targetFile
        }

        targetFile.withOutputStream {
            it << url.openStream()
        }

        log "Finished download into $targetFile, size ${targetFile.size()} bytes"

        targetFile
    }

    static void log(String s) {
        System.out.println "[${new Date()}] $s"
    }

    /* {{{ exec(Map args) */
    /**
     * Launch an external process
     * @param args
     * @return The data output on the process stdout and the exit value
     */
    static Map exec(Map args) {
        def command          = args.command
        File workingDir      = new File(args.workingDir ?: '.')
        def timeout          = args.timeout ?: 60000 /* 1 minute */
        def failOnStderrData = args.failOnStdErrData != null ? args.failOnStdErrData : true
        def suppressStdout   = args.suppressStdout != null ? args.suppressStdout : false
        def suppressStderr   = args.suppressStderr != null ? args.suppressStderr : false
        def throwOnFailure   = args.throwOnFailure != null ? args.throwOnFailure : true
        def stdoutRedirect   = args.stdoutRedirect ?: System.out
        def stderrRedirect   = args.stdoutRedirect ?: System.err
        File stdin           = new File('/dev/null')
        if (args.inputText) {
            stdin = File.createTempFile('buildImage_input', '')
            stdin << args.inputText
        }

        command = command.collect { it.toString() }

        def builder = new ProcessBuilder()
        builder.command(command).directory(workingDir).redirectInput(stdin)

        log "Executing $command with working dir $workingDir, timeout $timeout"
        Process p = builder.start()

        def hasErrorData = false;
        def stderrThread = new Thread(
                {
                    byte[] buffer = new byte[8192]
                    int read
                    while ((read = p.err.read(buffer)) != -1) {
                        if (!suppressStderr) {
                            stderrRedirect.write(buffer, 0, read)
                            stderrRedirect.flush()
                        }
                        hasErrorData = true
                    }
                } as Runnable
        );
        stderrThread.start()

        def stdoutData = new ByteArrayOutputStream()
        def stdoutThread =  new Thread(
                {
                    byte[] buffer = new byte[8192]
                    int read
                    while ((read = p.in.read(buffer)) != -1) {
                        if (!suppressStdout) {
                            stdoutRedirect.write(buffer, 0, read)
                            stdoutRedirect.flush()
                        }
                        stdoutData.write(buffer, 0, read)
                    }
                } as Runnable
        );
        stdoutThread.start()

        p.waitForOrKill(timeout)
        stderrThread.join()
        stdoutThread.join()

        def failed = false
        if (p.exitValue() != 0) {
            log "Command $command had a non-zero exit value: ${p.exitValue()}"
            failed = true
        } else if (failOnStderrData && hasErrorData) {
            log "Command $command has written to its stderr, interpreting this as an error condition"
            failed = true
        }

        if (throwOnFailure && failed) {
            throw new RuntimeException("Failure executing command $command")
        }

        return [
                stdoutData: stdoutData.toByteArray(),
                exitValue:  p.exitValue(),
                failed:     failed,
        ]
    }
    /* }}} */

    static void ensureCleanDirectory(File dir) {
        AntBuilder builder = new AntBuilder()
        if (dir.exists()) {
            log "Cleaning directory $dir"
            builder.delete dir: dir
        }
        builder.mkdir dir: dir
    }

    static void deleteOld(File cache, Pattern pattern) {
        def deleteAllButLatest = { Map<String, List<File>> map ->
            map.values().each {
                it.pop()
                it.each { file ->
                    if (!file.delete()) {
                        System.err.println "Could not delete $file"
                        System.exit 1
                    }
                }
            }
        }

        def foundByKey
        foundByKey = [:].withDefault {
            foundByKey[it] = []
        }
        cache.traverse(type: FILES,
                       nameFilter: pattern,
                       sort: { a,b -> a.lastModified() <=> b.lastModified() },
                       maxDepth: 0,
        ) {
            Matcher m = pattern.matcher(it.name)
            if (m.matches()) {
                foundByKey[m.group(1)] << it
            }
        }

        deleteAllButLatest foundByKey
    }
}

// vim: ts=4 sw=4 et tw=80
