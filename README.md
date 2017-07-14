# testrail-cli

## Description:
Uses the TestRail REST API v2 (Source: http://docs.gurock.com/testrail-api2/start) and org.openmbee:testrail-client-wrapper library to execute specific behavior from command line.

## Functions:
* JUnitPublisher parses JUnit XML test results and publishes them to a TestRail run. Test cases will be created if they are not found in the specified suite.
```
usage: JUnitPublisher
 -d,--directory <path>    Path to the directory that contains the JUnit
                          XML reports.
 -f,--file <path>         Path to a JUnit XML report.
 -h,--host <url>          The TestRail server address, e.g.
                          yoursubdomain.testrail.net
    --help
 -p,--password <arg>      The password or API key to authenticate to
                          TestRail with.
 -pid,--plan-id <id>      (Optional) The ID of the TestRail plan in which
                          to create the run.
    --run-name <string>   (Optional) The name of the TestRail run.
                          Defaults to current time in ISO-8601 format.
 -sid,--suite-id <id>     The ID of the TestRail suite in which to the
                          test cases exist or are to be created.
    --skip-close-run      (Optional) Providing this flag will keep the
                          TestRail run open after adding test results.
 -u,--user <arg>          The username or email address to authenticate to
                          TestRail with.
```

## Maven Setup
At JCenter repository to the `pom.xml`
```xml
<repositories>
    ...
    <repository
        <id>jcenter</id>
        <name>bintray</name>
        <url>https://jcenter.bintray.com/</url>
    </repository>
    ...
</repositories>
```

Add dependency to the `pom.xml`

```xml
<!-- https://jcenter.bintray.com/org/openmbee/testrail/ -->
<dependency>
    <groupId>org.openmbee.testrail</groupId>
    <artifactId>testrail-cli</artifactId>
    <version>1.0.0</version>
</dependency>
```

A final profile in a `pom.xml` might look something like
```xml
        <profile>
            <id>testrail</id>
            <properties>
                <skipTests>false</skipTests>
            </properties>
            <repositories>
                <repository>
                    <id>jcenter</id>
                    <name>bintray</name>
                    <url>https://jcenter.bintray.com/</url>
                </repository>
            </repositories>
            <dependencies>
                <!-- https://jcenter.bintray.com/org/openmbee/testrail/ -->
                <dependency>
                    <groupId>org.openmbee.testrail</groupId>
                    <artifactId>testrail-cli</artifactId>
                    <version>1.0.0</version>
                </dependency>
            </dependencies>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>exec-maven-plugin</artifactId>
                        <version>1.6.0</version>
                        <executions>
                            <execution>
                                <id>post-results-testrail</id>
                                <phase>post-integration-test</phase>
                                <goals>
                                    <goal>java</goal>
                                </goals>
                            </execution>
                        </executions>
                        <configuration>
                            <mainClass>org.openmbee.testrail.cli.JUnitPublisher</mainClass>
                            <arguments>
                                <!-- Equivalent to => -d dir -h host -u user -p pass -sid suite-id -pid plan-id -->
                                <argument>--directory</argument>  <argument>path/to/junit/output</argument>

                                <argument>--host</argument>   <argument>${env.TESTRAIL_HOST}</argument>

                                <argument>--user</argument><argument>${env.TESTRAIL_USER}</argument>

                                <argument>--password</argument><argument>${env.TESTRAIL_PASS}</argument>

                                <argument>--suite-id</argument><argument>${env.TESTRAIL_SUITE_ID}</argument>

                                <argument>--plan-id</argument><argument>${env.TESTRAIL_PLAN_ID}</argument>

                                <!--<argument>${env.TESTRAIL_RUN_NAME}</argument>-->
                            </arguments>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
