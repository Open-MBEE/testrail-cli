package org.openmbee.testrail.cli;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.cli.*;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.utils.URIBuilder;
import org.openmbee.junit.JUnitMarshalling;
import org.openmbee.junit.model.JUnitError;
import org.openmbee.junit.model.JUnitFailure;
import org.openmbee.junit.model.JUnitTestCase;
import org.openmbee.junit.model.JUnitTestSuite;
import org.openmbee.testrail.TestRailAPI;
import org.openmbee.testrail.model.*;

import javax.xml.bind.JAXBException;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class JUnitPublisher implements Runnable {
    private static Options infoOptions = new Options();
    private static Options options = new Options();

    private static Option helpOption = Option.builder().longOpt("help").build();
    private static Option hostOption = Option.builder("h").longOpt("host").required().hasArg().argName("url").desc("The TestRail server address, e.g. yoursubdomain.testrail.net").build();
    private static Option userOption = Option.builder("u").longOpt("user").required().hasArg().desc("The username or email address to authenticate to TestRail with.").build();
    private static Option passwordOption = Option.builder("p").longOpt("password").hasArg().desc("The password or API key to authenticate to TestRail with.").build();
    private static Option directoryOption = Option.builder("d").longOpt("directory").hasArg().argName("path").desc("Path to the directory that contains the JUnit XML reports.").build();
    private static Option fileOption = Option.builder("f").longOpt("file").hasArg().argName("path").desc("Path to a JUnit XML report.").build();
    private static Option suiteOption = Option.builder("sid").longOpt("suite-id").required().hasArg().argName("id").desc("The ID of the TestRail suite in which to the test cases exist or are to be created.").type(Number.class).build();
    private static Option planOption = Option.builder("pid").longOpt("plan-id").hasArg().argName("id").desc("(Optional) The ID of the TestRail plan in which to create the run.").type(Number.class).build();
    private static Option milestoneOption = Option.builder("m").longOpt("milestone").hasArg().argName("name").desc("(Optional) The name of the TestRail milestone to associate with the run.").build();
    private static Option runNameOption = Option.builder().longOpt("run-name").hasArg().argName("string").desc("(Optional) The name of the TestRail run. Defaults to current time in ISO-8601 format.").build();
    private static Option skipCloseRunOption = Option.builder().longOpt("skip-close-run").desc("(Optional) Providing this flag will keep the TestRail run open after adding test results.").type(Boolean.class).build();

    static {
        infoOptions.addOption(helpOption);

        options.addOption(helpOption);
        options.addOption(hostOption);
        options.addOption(userOption);
        options.addOption(passwordOption);
        options.addOption(directoryOption);
        options.addOption(fileOption);
        options.addOption(suiteOption);
        options.addOption(planOption);
        options.addOption(milestoneOption);
        options.addOption(runNameOption);
        options.addOption(skipCloseRunOption);
    }

    private final TestRailAPI api;
    private final int suiteId, planId;
    private final List<Path> reports;
    private final String milestoneName, runName;
    private final boolean skipCloseRun;

    public static void main(String... args) throws ParseException, URISyntaxException, IOException {
        CommandLineParser parser = new DefaultParser();
        CommandLine line = parser.parse(infoOptions, args, true);
        if (line.hasOption(helpOption.getLongOpt())) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(JUnitPublisher.class.getSimpleName(), options);
            System.exit(0);
            return;
        }
        line = parser.parse(options, args);
        String password = line.getOptionValue(passwordOption.getOpt());
        if (password == null) {
            if (System.console() != null) {
                password = new String(System.console().readPassword("Enter password: "));
            }
            else {
                System.out.println("Enter password: ");
                try (Scanner scanner = new Scanner(System.in)) {
                    password = scanner.nextLine();
                }
            }
        }
        String optionValue;
        List<Path> reports;
        if ((optionValue = line.getOptionValue(directoryOption.getOpt())) != null) {
            reports = Files.list(Paths.get(optionValue)).filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".xml")).sorted((p1, p2) -> {
                try {
                    return Files.getLastModifiedTime(p1).compareTo(Files.getLastModifiedTime(p2));
                } catch (IOException e) {
                    e.printStackTrace();
                    return 0;
                }
            }).collect(Collectors.toList());
        }
        else if ((optionValue = line.getOptionValue(fileOption.getOpt())) != null) {
            reports = Collections.singletonList(Paths.get(optionValue));
        }
        else {
            throw new IllegalArgumentException("Either directory or file must be specified. See --" + helpOption.getLongOpt() + " for more info.");
        }

        TestRailAPI api = new TestRailAPI(new URI(line.getOptionValue(hostOption.getOpt())), line.getOptionValue(userOption.getOpt()), password);
        int suiteId = ((Number) line.getParsedOptionValue(suiteOption.getOpt())).intValue();
        Object o;
        int planId = ((o = line.getParsedOptionValue(planOption.getOpt())) != null ? (Number) o : -1).intValue();
        String milestone = line.getOptionValue(milestoneOption.getLongOpt());
        String runName = line.getOptionValue(runNameOption.getLongOpt());
        boolean skipCloseRun = line.hasOption(skipCloseRunOption.getLongOpt());
        new JUnitPublisher(api, suiteId, planId, reports, milestone, runName, skipCloseRun).run();
    }

    @SneakyThrows({URISyntaxException.class, IOException.class, InterruptedException.class})
    @Override
    public void run() {
        TestRailSuite suite = api.getSuite(suiteId);
        if (suite == null) {
            throw new IllegalArgumentException("No test suite found for id " + suiteId);
        }

        List<TestRailSection> testRailSections;
        try {
            testRailSections = api.getSections(suite.getProjectId(), suite.getId());
        } catch (HttpResponseException e) {
            testRailSections = new ArrayList<>(reports.size());
        }
        Map<String, JUnitTestSuite> jUnitTestSuites = reports.stream().map(r -> {
            try (InputStream is = Files.newInputStream(r)) {
                return JUnitMarshalling.unmarshalTestSuite(is);
            } catch (IOException | JAXBException | XMLStreamException e) {
                e.printStackTrace();
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toMap(JUnitTestSuite::getName, Function.identity(), throwingMerger(), LinkedHashMap::new));
        List<JUnitTestCase> jUnitTestCases = jUnitTestSuites.values().stream().flatMap(ts -> ts.getTestCases() != null ? ts.getTestCases().stream() : Stream.empty()).collect(Collectors.toList());
        Map<String, List<JUnitTestCase>> jUnitTestCaseMap = jUnitTestCases.stream().map(JUnitTestCase::getClassName).distinct().map(className -> new AbstractMap.SimpleEntry<>(className, jUnitTestCases.stream().filter(tc -> tc.getClassName().equals(className)).collect(Collectors.toList()))).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, throwingMerger(), LinkedHashMap::new));

        Map<String, TestRailSection> testRailSectionMap = new LinkedHashMap<>(jUnitTestCaseMap.size());
        for (String name : jUnitTestCaseMap.keySet()) {
            TestRailSection section = testRailSections.stream().filter(s -> s.getName().equals(name) || s.getName().contains("@" + name) || s.getDescription() != null && s.getDescription().contains("@" + name)).findFirst().orElse(null);
            if (section == null) {
                TestRailSection requestSection = new TestRailSection().setSuiteId(suite.getId());
                String[] nameSections = name.split("\\.");
                requestSection.setName(nameSections[Math.max(nameSections.length - 1, 0)]);
                requestSection.setDescription("@" + name);
                section = api.addSection(requestSection, suite.getProjectId());
            }
            testRailSectionMap.put(name, section);
        }

        Map<TestRailSection, List<TestRailCase>> testRailCaseMap = new LinkedHashMap<>(testRailSectionMap.size());
        Map<JUnitTestCase, TestRailCase> caseMap = new LinkedHashMap<>(jUnitTestCases.size());
        for (Map.Entry<String, List<JUnitTestCase>> entry : jUnitTestCaseMap.entrySet()) {
            TestRailSection section = testRailSectionMap.get(entry.getKey());
            List<TestRailCase> testRailCases = api.getCases(suite.getProjectId(), suite.getId(), section.getId());
            testRailCaseMap.put(section, testRailCases);
            for (JUnitTestCase jUnitTestCase : entry.getValue()) {
                TestRailCase testRailCase = testRailCases.stream().filter(c -> c.getTitle().equals(jUnitTestCase.getName()) || c.getTitle().contains("@" + jUnitTestCase.getName())).findFirst().orElse(null);
                if (testRailCase == null) {
                    TestRailCase requestCase = new TestRailCase().setSuiteId(suite.getId()).setSectionId(section.getId()).setTitle(jUnitTestCase.getName());
                    final long sleepMillis = 750L;
                    System.out.println("Sleeping " + sleepMillis + " milliseconds and then adding case: " + entry.getKey() + "#" + jUnitTestCase.getName());
                    Thread.sleep(sleepMillis);
                    testRailCase = api.addCase(requestCase);
                }
                caseMap.put(jUnitTestCase, testRailCase);
            }
        }

        TestRailUser user = api.getCurrentUser();

        TestRailRun requestRun = new TestRailRun().setProjectId(suite.getProjectId()).setSuiteId(suite.getId());

        TestRailPlan plan = planId > 0 ? api.getPlan(planId) : null;
        if (plan != null) {
            requestRun.setPlanId(plan.getId());
        }
        if (milestoneName != null) {
            TestRailMilestone milestone = api.getMilestones(suite.getProjectId()).stream().filter(m -> m.getName().equals(milestoneName) || m.getName().contains("@" + milestoneName) || m.getDescription() != null && m.getDescription().contains("@" + milestoneName)).findFirst().orElse(null);
            if (milestone == null) {
                TestRailMilestone requestMilestone = new TestRailMilestone();
                requestMilestone.setName(milestoneName);
                requestMilestone.setProjectId(suite.getProjectId());
                milestone = api.addMilestone(requestMilestone);
            }
            requestRun.setMilestoneId(milestone.getId());
        }

        requestRun.setName(runName != null ? runName : LocalDateTime.now().toString()).setDescription(jUnitTestSuites.entrySet().stream().map(entry -> {
            JUnitTestSuite jUnitTestSuite = entry.getValue();
            StringBuilder descriptionBuilder = new StringBuilder();
            descriptionBuilder.append("# ").append(entry.getKey()).append(" #").append(System.lineSeparator());
            int propertyCount = jUnitTestSuite.getProperties() != null ? jUnitTestSuite.getProperties().size() : 0;
            descriptionBuilder.append("__Properties:__ ").append(NumberFormat.getInstance().format(propertyCount)).append(System.lineSeparator());
            if (propertyCount > 0) {
                descriptionBuilder.append(formatCodeString(jUnitTestSuite.getProperties().stream().map(property -> property.getName() + "=" + property.getValue()).collect(Collectors.joining(System.lineSeparator())))).append(System.lineSeparator());
            }
            descriptionBuilder.append("__System Out:__ ").append(System.lineSeparator());
            descriptionBuilder.append(formatCodeString(jUnitTestSuite.getSystemOut())).append(System.lineSeparator());
            descriptionBuilder.append("__System Err:__ ").append(System.lineSeparator());
            descriptionBuilder.append(formatCodeString(jUnitTestSuite.getSystemErr())).append(System.lineSeparator());
            return descriptionBuilder.toString();
        }).collect(Collectors.joining(System.lineSeparator())))
                .setAssignedToId(user.getId()).setIncludeAll(false).setCaseIds(caseMap.values().stream().map(TestRailCase::getId).collect(Collectors.toList()));
        TestRailRun run = api.addRun(requestRun);

        List<TestRailTest> tests = api.getTests(run.getId());
        Map<JUnitTestCase, TestRailTest> testMap = caseMap.entrySet().stream().map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), tests.stream().filter(test -> entry.getValue().getId().equals(test.getCaseId())).findAny().orElseThrow(() -> new NullPointerException("No TestRailTest matching JUnitTestCase " + entry.getKey().getName() + ".")))).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, throwingMerger(), LinkedHashMap::new));

        final List<TestRailResult> requestResults = new ArrayList<>();

        testMap.entrySet().stream().forEach(entry -> {
            JUnitTestCase jUnitTestCase = entry.getKey();
            if (jUnitTestCase.getSkipped() == null) {
                TestRailResult requestResult = new TestRailResult().setTestId(entry.getValue().getId()).setStatusId((jUnitTestCase.getErrors() == null || jUnitTestCase.getErrors().isEmpty()) && (jUnitTestCase.getFailures() == null || jUnitTestCase.getFailures().isEmpty()) ? 1 : 5);
                int errorCount = jUnitTestCase.getErrors() != null ? jUnitTestCase.getErrors().size() : 0;
                StringBuilder commentBuilder = new StringBuilder();
                commentBuilder.append("__Errors:__ ").append(NumberFormat.getInstance().format(errorCount)).append(System.lineSeparator());
                for (int i = 0; i < errorCount; i++) {
                    JUnitError error = jUnitTestCase.getErrors().get(i);
                    commentBuilder.append(i + 1).append(") Message: ").append(error.getMessage()).append(" | Type: ").append(error.getType()).append(System.lineSeparator());
                    commentBuilder.append(formatCodeString(error.getValue())).append(System.lineSeparator());
                }
                int failureCount = jUnitTestCase.getFailures() != null ? jUnitTestCase.getFailures().size() : 0;
                commentBuilder.append("__Failures:__ ").append(NumberFormat.getInstance().format(failureCount)).append(System.lineSeparator());
                for (int i = 0; i < failureCount; i++) {
                    JUnitFailure failure = jUnitTestCase.getFailures().get(i);
                    commentBuilder.append(i + 1).append(") Message: ").append(failure.getMessage()).append(" | Type: ").append(failure.getType()).append(System.lineSeparator());
                    commentBuilder.append(formatCodeString(failure.getValue())).append(System.lineSeparator());
                }
                commentBuilder.append("__System Out:__ ").append(System.lineSeparator());
                commentBuilder.append(formatCodeString(jUnitTestCase.getSystemOut())).append(System.lineSeparator());
                commentBuilder.append("__System Err:__ ").append(System.lineSeparator());
                commentBuilder.append(formatCodeString(jUnitTestCase.getSystemErr()));
                requestResult.setComment(commentBuilder.toString());
                if (jUnitTestCase.getTime() != null) {
                    requestResult.setElapsed(Math.max(Math.round(jUnitTestCase.getTime()), 1) + "s");
                }
                requestResult.setAssignedToId(user.getId());

                requestResults.add(requestResult);
            }
        });

        List<TestRailResult> results = new ArrayList<>();

        try {
          results = api.addResults(requestResults, run.getId());
        } catch (URISyntaxException | IOException e) {
          throw new RuntimeException(e);
        }

        if (!skipCloseRun) {
            run = api.closeRun(run);
        }

        URIBuilder runUriBuilder = new URIBuilder(api.getUri());
        runUriBuilder.setParameter("/runs/view/" + run.getId(), null);
        System.out.println("Published test run on TestRail: " + runUriBuilder.build().toString());
    }

    private String formatCodeString(String code) {
        return System.lineSeparator() + (code != null ? Arrays.stream(code.split("\\r?\\n")).map(line -> "    " + line).collect(Collectors.joining(System.lineSeparator())) : "    null") + System.lineSeparator();
    }

    private static <T> BinaryOperator<T> throwingMerger() {
        return (u, v) -> {
            throw new IllegalStateException(String.format("Duplicate key %s", u));
        };
    }
}
