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
```# testrail-cli
