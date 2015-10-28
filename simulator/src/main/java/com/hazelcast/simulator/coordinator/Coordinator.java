/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.coordinator;

import com.hazelcast.simulator.cluster.AgentWorkerLayout;
import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.protocol.connector.CoordinatorConnector;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.registry.AgentData;
import com.hazelcast.simulator.protocol.registry.ComponentRegistry;
import com.hazelcast.simulator.test.TestCase;
import com.hazelcast.simulator.test.TestPhase;
import com.hazelcast.simulator.test.TestSuite;
import com.hazelcast.simulator.utils.Bash;
import com.hazelcast.simulator.utils.CommandLineExitException;
import com.hazelcast.simulator.utils.ThreadSpawner;
import com.hazelcast.simulator.worker.WorkerType;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static com.hazelcast.simulator.cluster.ClusterUtils.initMemberLayout;
import static com.hazelcast.simulator.common.GitInfo.getBuildTime;
import static com.hazelcast.simulator.common.GitInfo.getCommitIdAbbrev;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.FINISHED_WORKER_TIMEOUT_SECONDS;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.getTestPhaseSyncMap;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.logFailureInfo;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.waitForWorkerShutdown;
import static com.hazelcast.simulator.protocol.configuration.Ports.AGENT_PORT;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isEC2;
import static com.hazelcast.simulator.utils.CommonUtils.exitWithError;
import static com.hazelcast.simulator.utils.CommonUtils.getElapsedSeconds;
import static com.hazelcast.simulator.utils.CommonUtils.getSimulatorVersion;
import static com.hazelcast.simulator.utils.CommonUtils.rethrow;
import static com.hazelcast.simulator.utils.CommonUtils.sleepSeconds;
import static com.hazelcast.simulator.utils.FileUtils.getSimulatorHome;
import static com.hazelcast.simulator.utils.FormatUtils.HORIZONTAL_RULER;
import static com.hazelcast.simulator.utils.FormatUtils.secondsToHuman;
import static com.hazelcast.simulator.utils.HarakiriMonitorUtils.getStartHarakiriMonitorCommandOrNull;
import static java.lang.String.format;

public final class Coordinator {

    static final String SIMULATOR_VERSION = getSimulatorVersion();

    private static final Logger LOGGER = Logger.getLogger(Coordinator.class);

    private final TestPhaseListenerContainer testPhaseListenerContainer = new TestPhaseListenerContainer();
    private final PerformanceStateContainer performanceStateContainer = new PerformanceStateContainer();
    private final TestHistogramContainer testHistogramContainer = new TestHistogramContainer(performanceStateContainer);

    private final TestSuite testSuite;
    private final ComponentRegistry componentRegistry;
    private final CoordinatorParameters coordinatorParameters;
    private final WorkerParameters workerParameters;
    private final ClusterLayoutParameters clusterLayoutParameters;

    private final FailureContainer failureContainer;

    private final SimulatorProperties props;
    private final Bash bash;

    private final List<AgentWorkerLayout> agentWorkerLayouts;
    private final int memberWorkerCount;
    private final int clientWorkerCount;

    private RemoteClient remoteClient;
    private CoordinatorConnector coordinatorConnector;

    public Coordinator(TestSuite testSuite, ComponentRegistry componentRegistry, CoordinatorParameters coordinatorParameters,
                       WorkerParameters workerParameters, ClusterLayoutParameters clusterLayoutParameters) {
        this.testSuite = testSuite;
        this.componentRegistry = componentRegistry;
        this.coordinatorParameters = coordinatorParameters;
        this.workerParameters = workerParameters;
        this.clusterLayoutParameters = clusterLayoutParameters;

        this.failureContainer = new FailureContainer(testSuite, componentRegistry);

        this.props = coordinatorParameters.getSimulatorProperties();
        this.bash = new Bash(props);

        this.agentWorkerLayouts = initMemberLayout(componentRegistry, workerParameters, clusterLayoutParameters,
                clusterLayoutParameters.getMemberWorkerCount(), clusterLayoutParameters.getClientWorkerCount());

        int tmpMemberCount = 0;
        int tmpClientCount = 0;
        for (AgentWorkerLayout agentWorkerLayout : agentWorkerLayouts) {
            tmpMemberCount += agentWorkerLayout.getCount(WorkerType.MEMBER);
            tmpClientCount += agentWorkerLayout.getCount(WorkerType.CLIENT);
        }
        this.memberWorkerCount = tmpMemberCount;
        this.clientWorkerCount = tmpClientCount;

        logConfiguration();
    }

    CoordinatorParameters getCoordinatorParameters() {
        return coordinatorParameters;
    }

    WorkerParameters getWorkerParameters() {
        return workerParameters;
    }

    ClusterLayoutParameters getClusterLayoutParameters() {
        return clusterLayoutParameters;
    }

    TestSuite getTestSuite() {
        return testSuite;
    }

    ComponentRegistry getComponentRegistry() {
        return componentRegistry;
    }

    FailureContainer getFailureContainer() {
        return failureContainer;
    }

    PerformanceStateContainer getPerformanceStateContainer() {
        return performanceStateContainer;
    }

    RemoteClient getRemoteClient() {
        return remoteClient;
    }

    // just for testing
    void setRemoteClient(RemoteClient remoteClient) {
        this.remoteClient = remoteClient;
    }

    // just for testing
    TestPhaseListenerContainer getTestPhaseListenerContainer() {
        return testPhaseListenerContainer;
    }

    private void logConfiguration() {
        boolean performanceEnabled = workerParameters.isMonitorPerformance();
        int performanceIntervalSeconds = workerParameters.getWorkerPerformanceMonitorIntervalSeconds();
        echoLocal("Performance monitor enabled: %s (%d seconds)", performanceEnabled, performanceIntervalSeconds);

        echoLocal("Total number of agents: %s", componentRegistry.agentCount());
        echoLocal("Total number of Hazelcast member workers: %s", memberWorkerCount);
        echoLocal("Total number of Hazelcast client workers: %s", clientWorkerCount);

        echoLocal("HAZELCAST_VERSION_SPEC: %s", props.getHazelcastVersionSpec());
    }

    private void run() throws Exception {
        try {
            uploadFiles();

            startAgents();
            startWorkers();

            runTestSuite();
            logFailureInfo(failureContainer.getFailureCount());
        } finally {
            shutdown();
        }
    }

    private void uploadFiles() {
        CoordinatorUploader uploader = new CoordinatorUploader(componentRegistry, bash, testSuite.getId(), null,
                coordinatorParameters.isEnterpriseEnabled(), coordinatorParameters.getWorkerClassPath(),
                workerParameters.getProfiler());
        uploader.run();
    }

    private void startAgents() {
        echoLocal("Starting %s Agents", componentRegistry.agentCount());
        ThreadSpawner spawner = new ThreadSpawner("startAgents", true);
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    startAgent(agentData.getAddressIndex(), agentData.getPublicAddress());
                }
            });
        }
        spawner.awaitCompletion();
        echoLocal("Successfully started agents on %s boxes", componentRegistry.agentCount());

        try {
            startCoordinatorConnector();
        } catch (Exception e) {
            throw new CommandLineExitException("Could not start CoordinatorConnector", e);
        }

        remoteClient = new RemoteClient(coordinatorConnector, componentRegistry);
        remoteClient.initTestSuite(testSuite);
    }

    private void startAgent(int addressIndex, String ip) {
        echoLocal("Killing Java processes on %s", ip);
        bash.killAllJavaProcesses(ip);

        echoLocal("Starting Agent on %s", ip);
        String mandatoryParameters = format("--addressIndex %d --publicAddress %s", addressIndex, ip);
        String optionalParameters = "";
        if (isEC2(props.get("CLOUD_PROVIDER"))) {
            optionalParameters = format(" --cloudProvider %s --cloudIdentity %s --cloudCredential %s",
                    props.get("CLOUD_PROVIDER"),
                    props.get("CLOUD_IDENTITY"),
                    props.get("CLOUD_CREDENTIAL"));
        }
        bash.ssh(ip, format("nohup hazelcast-simulator-%s/bin/agent %s%s > agent.out 2> agent.err < /dev/null &",
                SIMULATOR_VERSION, mandatoryParameters, optionalParameters));

        bash.ssh(ip, format("hazelcast-simulator-%s/bin/.await-file-exists agent.pid", SIMULATOR_VERSION));
    }

    private void startCoordinatorConnector() {
        coordinatorConnector = new CoordinatorConnector(testPhaseListenerContainer, performanceStateContainer,
                testHistogramContainer, failureContainer);
        ThreadSpawner spawner = new ThreadSpawner("startCoordinatorConnector", true);
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    coordinatorConnector.addAgent(agentData.getAddressIndex(), agentData.getPublicAddress(), AGENT_PORT);
                }
            });
        }
        spawner.awaitCompletion();
    }

    private void startWorkers() {
        int totalWorkerCount = memberWorkerCount + clientWorkerCount;

        long started = System.nanoTime();
        try {
            echo("Killing all remaining workers");
            remoteClient.terminateWorkers(false);
            echo("Successfully killed all remaining workers");

            echo("Starting %d workers (%d members, %d clients)", totalWorkerCount, memberWorkerCount, clientWorkerCount);
            remoteClient.createWorkers(agentWorkerLayouts, true);
            echo("Successfully started workers");
        } catch (Exception e) {
            while (failureContainer.getFailureCount() == 0) {
                sleepSeconds(1);
            }
            throw new CommandLineExitException("Failed to start workers", e);
        }

        long elapsed = getElapsedSeconds(started);
        LOGGER.info((format("Successfully started a grand total of %s worker JVMs (%s seconds)", totalWorkerCount, elapsed)));
    }

    void runTestSuite() {
        boolean isParallel = coordinatorParameters.isParallel();
        int testCount = testSuite.size();
        int maxTestCaseIdLength = testSuite.getMaxTestCaseIdLength();

        TestPhase lastTestPhaseToSync = coordinatorParameters.getLastTestPhaseToSync();
        ConcurrentMap<TestPhase, CountDownLatch> testPhaseSyncs = getTestPhaseSyncMap(isParallel, testCount, lastTestPhaseToSync);

        echo("Starting testsuite: %s", testSuite.getId());
        logTestSuiteDuration();

        echo(HORIZONTAL_RULER);
        echo("Running %s tests (%s)", testCount, isParallel ? "parallel" : "sequentially");
        echo(HORIZONTAL_RULER);

        for (TestCase testCase : testSuite.getTestCaseList()) {
            echo(format("Configuration for test: %s%n%s", testCase.getId(), testCase));
            TestCaseRunner runner = new TestCaseRunner(testCase, this, maxTestCaseIdLength, testPhaseSyncs);
            testPhaseListenerContainer.addListener(testCase.getId(), runner);
        }
        echo(HORIZONTAL_RULER);

        long started = System.nanoTime();
        if (isParallel) {
            runParallel();
        } else {
            runSequential();
        }

        remoteClient.terminateWorkers(true);
        Set<SimulatorAddress> finishedWorkers = failureContainer.getFinishedWorkers();
        if (!waitForWorkerShutdown(componentRegistry.workerCount(), finishedWorkers, FINISHED_WORKER_TIMEOUT_SECONDS)) {
            LOGGER.warn(format("Unfinished workers: %s", componentRegistry.getMissingWorkers(finishedWorkers).toString()));
        }

        performanceStateContainer.logDetailedPerformanceInfo();
        for (TestCase testCase : testSuite.getTestCaseList()) {
            testHistogramContainer.createProbeResults(testSuite.getId(), testCase.getId());
        }

        echo(format("Total running time: %s seconds", getElapsedSeconds(started)));
    }

    private void logTestSuiteDuration() {
        int testDuration = testSuite.getDurationSeconds();
        if (testDuration > 0) {
            echo("Running time per test: %s", secondsToHuman(testDuration));
            int totalDuration = (coordinatorParameters.isParallel()) ? testDuration : testDuration * testSuite.size();
            if (testSuite.isWaitForTestCase()) {
                echo("Testsuite will run until tests are finished for a maximum time of: %s", secondsToHuman(totalDuration));
            } else {
                echo("Expected total testsuite time: %s", secondsToHuman(totalDuration));
            }
        } else if (testSuite.isWaitForTestCase()) {
            echo("Testsuite will run until tests are finished");
        }
    }

    private void runParallel() {
        ThreadSpawner spawner = new ThreadSpawner("runParallel", true);
        for (final TestPhaseListener testCaseRunner : testPhaseListenerContainer.getListeners()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    try {
                        ((TestCaseRunner) testCaseRunner).run();
                        if (failureContainer.hasCriticalFailure() && testSuite.isFailFast()) {
                            LOGGER.info("Aborting testsuite due to failure (not implemented yet)");
                            // FIXME: we should abort here as logged
                        }
                    } catch (Exception e) {
                        throw rethrow(e);
                    }
                }
            });
        }
        spawner.awaitCompletion();
    }

    private void runSequential() {
        for (TestPhaseListener testCaseRunner : testPhaseListenerContainer.getListeners()) {
            ((TestCaseRunner) testCaseRunner).run();
            boolean hasCriticalFailure = failureContainer.hasCriticalFailure();
            if (hasCriticalFailure && testSuite.isFailFast()) {
                LOGGER.info("Aborting testsuite due to critical failure");
                break;
            }
            if (hasCriticalFailure || coordinatorParameters.isRefreshJvm()) {
                startWorkers();
            }
        }
    }

    private void shutdown() throws Exception {
        if (coordinatorConnector != null) {
            LOGGER.info("Shutdown of ClientConnector...");
            coordinatorConnector.shutdown();
        }

        stopAgents();
    }

    private void stopAgents() {
        final String startHarakiriMonitorCommand = getStartHarakiriMonitorCommandOrNull(props);

        echoLocal("Stopping %s Agents", componentRegistry.agentCount());
        ThreadSpawner spawner = new ThreadSpawner("killAgents", true);
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    String ip = agentData.getPublicAddress();
                    echoLocal("Stopping Agent %s", ip);
                    bash.ssh(ip, format("hazelcast-simulator-%s/bin/.kill-from-pid-file agent.pid", SIMULATOR_VERSION));

                    if (startHarakiriMonitorCommand != null) {
                        LOGGER.info(format("Starting HarakiriMonitor on %s", ip));
                        bash.ssh(ip, startHarakiriMonitorCommand);
                    }
                }
            });
        }
        spawner.awaitCompletion();
        echoLocal("Successfully stopped %s Agents", componentRegistry.agentCount());
    }

    private void echoLocal(String msg, Object... args) {
        LOGGER.info(format(msg, args));
    }

    private void echo(String msg, Object... args) {
        String message = format(msg, args);
        remoteClient.logOnAllAgents(message);
        LOGGER.info(message);
    }

    public static void main(String[] args) {
        LOGGER.info("Hazelcast Simulator Coordinator");
        LOGGER.info(format("Version: %s, Commit: %s, Build Time: %s", SIMULATOR_VERSION, getCommitIdAbbrev(), getBuildTime()));
        LOGGER.info(format("SIMULATOR_HOME: %s", getSimulatorHome()));

        try {
            Coordinator coordinator = CoordinatorCli.init(args);
            coordinator.run();
        } catch (Exception e) {
            exitWithError(LOGGER, "Failed to run testsuite", e);
        }
    }
}
