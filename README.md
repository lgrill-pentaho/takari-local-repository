# Takari Concurrent Local Repository

The Takari Concurrent Local Repository component is a replacement for the management of a local Maven repository 
of a default Maven installation. It makes access to the local repository safe for concurrent access from multiple 
threads and Maven invocations. 

Documentation for usage and more is available in the Takari TEAM documentation at http://takari.io/book/index.html

http://takari.io/book/30-team-maven.html#concurrent-safe-local-rConcurrent Safe Local Repository

The local repository used by Maven is, by default, stored in the users home directory in .m2/repository . It acts as a cache for dependencies and plugins, that have been retrieved from remote repositories, as well as a storage location for build outputs from locally built projects. These can then be used by other Maven projects accessing the local repository.

The access to the local repository performed by a standard Maven installation is not designed to support multiple instances of Maven or even multiple threads from the same Maven invocation accessing it concurrently. Concurrent access can end up corrupting the consistency of the repository due to wrong metadata file content and similar problems.

The Takari concurrent local repository support, available from https://github.com/takari/takari-local-repository, removes this restriction and enables safe concurrent use of the local repository. Multiple builds and threads can concurrently resolve and install artifacts to the shared local repository. This is especially useful for continuous integration systems that usually build multiple projects in parallel and want to share the same local repository to reduce disk consumption.

Note that this extension is only concerned with the data integrity of the local repository at the artifact/metadata file level. It does not provide all-or-nothing installation of artifacts produced by a given build.

# Installation and Usage
To use the Takari local repository access, you must install it in Maven’s lib/ext folder, by downloading the jar files from the Central Repository and moving them into place:

Original:
curl -O http://repo1.maven.org/maven2/io/takari/aether/takari-local-repository/0.10.4/takari-local-repository-0.10.4.jar

Hitachi-Vantara (Pentaho):
curl -O https://nexus.pentaho.org/repository/public-release/org/hitachi/aether/takari-local-repository/0.12.0/takari-local-repository-0.12.0.jar

mv takari-local-repository-<version>.jar $M2_HOME/lib/ext
 
curl -O http://repo1.maven.org/maven2/io/takari/takari-filemanager/0.8.2/takari-filemanager-0.8.2.jar
mv takari-filemanager-0.8.2.jar $M2_HOME/lib/ext

Once the extensions are installed, no further steps are required. Any access to the local repository is automatically performed in a process/thread safe manner.



