# Please Read Me #
* Implementation:
    * The examples are implemented with Java 11, JDK 11.
    * The examples use REST and HATEOAS.
    * The examples apply a lot of code repetition to keep them self contained.
    * All examples are configured to use a connection timeout and a read/socket timeout of 60s each.
    * When running the examples it is required to pass an HTTP basic auth string via the command line. The HTTP basic auth string token can be obtained from Avid.
    * If appropriate for an example, the CTMS Registry is queried for the resource in question, instead using a hard coded URL.
        * It is assumed that the CTMS Registry runs in version 0 (hard coded).
        * If the CTMS Registry is unreachable or the resource in question cannot be found, a default URL template will be applied.
    * There are some error checks but those are very general and might not cover all cases. Esp. timeouts or unreachable endpoints could happen at any time during the application of REST and HATEOAS.
    * No optimization and no parallelization (e.g. for requesting results from the platform) was implemented.
        * Esp. the examples use HATEOAS to get all links. Instead of HATEOAS all links could be used hard coded or being "bookmarked" without HATEOAS (resulting in faster execution time), but this is not the idea behind RESTful interfaces. Also mind, that those links could change in future so the only save way is to get the via HATEOAS. The examples do only use these URLs directly: https://$apidomain/auth, https://$apidomain/api/middleware/service/ping, https://$apidomain/apis/$servicetype;version=0;realm=$realm/locations and https://$apidomain/apis/$servicetype;version=0;realm=$realm/searches other URLs are resolved via HATEOAS!
    * For testing purposes, it was required to configure HTTP libraries to accept arbitrary SSL certificates. Please notice, that this may not be acceptable for productive code.

* Dependencies:
    * Additionally, Handy URI Templates 2.1.8, resteasy-client 4.7.1.Final, unirest-java:3.11.12, reactor-core:3.4.9 and jackson-core:2.12.4 were used. The gradle project is self contained and will automatically download dependent libraries from maven.

* Using gradle/gradlew to build and run the examples:
    * (Checked with gradle 6.5.1.)
        * Creating an IntelliJ IDEA project from the sources, to allow easy code inspection and debugging:
            * On the terminal cd to the directory where the examples reside and issue "gradlew idea".
            * This command will get dependent libraries from maven and create a file-based Idea project with subprojects for each example and one subproject with some tools.
            * When running the examples in the IDE, make sure you have specified correct command line arguments: _apidomain_ _httpbasicauthstring_ [_servicetype_] _realm_
        * Creating runnable jars from the sources to run the examples w/o IDE:
            * On the terminal cd to the directory where the examples reside and issue "gradlew build".
            * This command will get dependent libraries from maven and create runnable jars in directory "dest".

* Special remarks on running the examples:
    * => When running the jars on a terminal, make sure you have specified correct command line arguments: java -jar __Example.jar__ _apidomain_ _httpbasicauthstring_ _[servicetype]_ _[realm]_
    * The QueryServiceRegistry example needs no servicetype (always "avid.ctms.registry") and no realm (always "global"/"") argument.
        * java -jar QueryServiceRegistry.jar _apidomain_ _httpbasicauthstring_ _serviceversion_
        * Example: java -jar QueryServiceRegistry.jar upstream httpbasicauthstring 0
    * Optionally, e.g. for debugging purposes, the JVM can be started with the VM arguments _-Dhttps.proxyHost=localhost -Dhttps.proxyPort=8888_ to configure a proxy server.
        * Notice, that using a proxy can reduce the performance of HTTP requests.
        * Notice also, that having set proxy options as shown above while *no proxy* is configured can reduce the performance of HTTP requests by an order of magnitude!
        
    Todos:
    * Add an example using Java 11's HTTP/2 API with reactive interface (https://blog.codefx.org/java/reactive-http-2-requests-responses/).
