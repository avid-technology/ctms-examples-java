# Please Read Me #
* Implementation:
    * The examples are implemented with Java 8, JDK 8.
    * The examples use REST and HATEOAS.
    * The example SimpleSearchAsync shows, how asynchronous HTTP requests can be applied to use CTMS with reasteasy.
    * The example SimpleSearchAsyncUnirest shows, how asynchronous HTTP requests can be applied to use CTMS with Unirest. (Unirest is the recommended API for async. communication.)
    * The examples FileCheckIn and FolderOperationsUnirest use Unirest, esp. because the HTTP verb "PATCH" is not supported in the JDK and Unirest closed the gap.
    * All examples are configured to use a connection timeout and a read/socket timeout of 60s each.
    * If appropriate for an example, the CTMS Registry is queried for the resource in question, instead using a hard coded URL.
        * It is assumed that the CTMS Registry runs in version 0 (hard coded).
        * If the CTMS Registry is unreachable or the resource in question cannot be found, a default URL template will be applied.
    * There are some error checks but those are very general and might not cover all cases. Esp. timeouts or unreachable endpoints could happen at any time during the application of REST and HATEOAS.
    * No optimization and no parallelization (e.g. for requesting results from the platform) was implemented.
        * Esp. the examples use HATEOAS to get all links. Instead of HATEOAS all links could be used hard coded or being "bookmarked" without HATEOAS (resulting in faster execution time), but this is not the idea behind RESTful interfaces. Also mind, that those links could change in future so the only save way is to get the via HATEOAS. The examples do only use these URLs directly: https://$apidomain/auth, https://$apidomain/api/middleware/service/ping, https://$apidomain/apis/$servicetype;version=0;realm=$realm/locations and https://$apidomain/apis/$servicetype;version=0;realm=$realm/searches other URLs are resolved via HATEOAS!
    * For testing purposes, it was required to configure the HttpsURLConnections to accept arbitrary SSL certificates. Please notice, that this may not be acceptable for productive code.

* Dependencies:
    * Additionally, Handy URI Templates 2.1.2, json-lib 2.4, resteasy-client 3.0.19.Final and unirest-java:1.3.28 were used. The gradle project is self contained and will automatically download dependent libraries from maven.

* Using gradle to build an run the examples:
    * (Checked with gradle 2.14.)
        * Creating an IntelliJ IDEA project from the sources, to allow easy code inspection and debugging:
            * On the terminal cd to the directory where the examples reside and issue "gradle idea".
            * This command will get dependent libraries from maven (e.g. json-lib 2.4) and create a file-based Idea project with subprojects for each example and one subproject with some tools.
            * When running the examples in the IDE, make sure you have specified correct command line arguments: _apidomain_ _servicetype_ _realm_ _username_ _password_
        * Creating runnable jars from the sources to run the examples w/o IDE:
            * On the terminal cd to the directory where the examples reside and issue "gradle build".
            * This command will get dependent libraries from maven (e.g. json-lib 2.4) and create runnable jars in directory "dest".

* Special remarks on running the examples:
    * => When running the jars on a terminal, make sure you have specified correct command line arguments: java -jar __Example.jar__ _apidomain_ _[servicetype]_ _[realm]_ _username_ _password_ '_[searchexpression]_' _[advancedsearchdescriptionfilename]_
    * The SimpleSearch/SimpleSearchAsync examples await the searchexpression in single quotes as last argument:
        * java -jar SimpleSearch.jar _apidomain_ _servicetype_ _realm_ _username_ _password_ '_searchexpression_'
        * Example: java -jar SimpleSearch.jar upstream avid.mam.assets.access BEEF Administrator ABRAXAS "'*'"
        * java -jar SimpleSearchAsync.jar _apidomain_ _servicetype_ _realm_ _username_ _password_ '_searchexpression_'
        * Example: java -jar SimpleSearchAsync.jar upstream avid.mam.assets.access BEEF Administrator ABRAXAS "'*'"
    * The AdvancedSearch example awaits the file name of a file containing the advanced search description as last argument:
        * java -jar AdvancedSearch.jar _apidomain_ _servicetype_ _realm_ _username_ _password_ _advancedsearchdescriptionfilename_
        * Example: java -jar AdvancedSearch.jar upstream avid.mam.assets.access BEEF Administrator ABRAXAS resources\MAMAdvancedSearchDescription.txt
    * The Orchestration example (jar) contains multiple executable Java classes: com.avid.ctms.examples.orchestration.queryprocesses.QueryProcesses and com.avid.ctms.examples.orchestration.startprocesses.StartProcess. Those have to be executed with a different command line, esp. without servicetype, their servicetype is always "avid.orchestration.ctc" and with java's "-cp" option:
        * java -cp Orchestration.jar _mainclassname_ _realm_ _username_ _password_ ['_searchexpression_']
        * Example: java -cp Orchestration.jar com.avid.ctms.examples.orchestration.queryprocesses.QueryProcesses upstream BEEF Administrator ABRAXAS "'*'"
        * Example: java -cp Orchestration.jar com.avid.ctms.examples.orchestration.startprocesses.StartProcess upstream BEEF Administrator ABRAXAS
    * The FileCheckIn example needs no servicetype (always "avid.mam.assets.access") argument.
        * java -jar FileCheckIn.jar _apidomain_ _realm_ _username_ _password_ _sourcepath_
            * _sourcepath_ represents the path, where the file to be checked in resides. If the path contains backslashes, it is required to escape it for three times each, e.g. one backslash must be represented by four backslashes.
        * java -jar FileCheckIn.jar upstream BEEF Administrator ABRAXAS "\\\\\\\\nas4\\\\MAMSTORE\\\\mam-b\\\\MediaAssetManager\\\\Terminator.jpg"
    * The QueryServiceRegistry example needs no servicetype (always "avid.ctms.registry") and no realm (always "global"/"") argument.
        * node java -jar QueryServiceRegistry.jar _apidomain_ _username_ _password_
        * Example: java -jar QueryServiceRegistry.jar upstream Administrator ABRAXAS
    * Optionally, e.g. for debugging purposes, the JVM can be started with the VM arguments _-Dhttps.proxyHost=localhost -Dhttps.proxyPort=8888_ to configure a proxy server.
        * Notice, that using a proxy can reduce the performance of HTTP requests.
        * Notice also, that having set proxy options as shown above while *no proxy* is configured can reduce the performance of HTTP requests by an order of magnitude!