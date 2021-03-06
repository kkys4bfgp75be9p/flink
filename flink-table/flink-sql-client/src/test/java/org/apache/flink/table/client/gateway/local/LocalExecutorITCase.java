/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.table.client.gateway.local;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.client.cli.DefaultCLI;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.configuration.WebOptions;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.SqlDialect;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.config.OptimizerConfigOptions;
import org.apache.flink.table.api.config.TableConfigOptions;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.client.config.Environment;
import org.apache.flink.table.client.config.entries.ExecutionEntry;
import org.apache.flink.table.client.gateway.Executor;
import org.apache.flink.table.client.gateway.ProgramTargetDescriptor;
import org.apache.flink.table.client.gateway.ResultDescriptor;
import org.apache.flink.table.client.gateway.SessionContext;
import org.apache.flink.table.client.gateway.SqlExecutionException;
import org.apache.flink.table.client.gateway.TypedResult;
import org.apache.flink.table.client.gateway.utils.EnvironmentFileUtil;
import org.apache.flink.table.client.gateway.utils.SimpleCatalogFactory;
import org.apache.flink.table.client.gateway.utils.TestUserClassLoaderJar;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.planner.factories.utils.TestCollectionTableFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.test.util.TestBaseUtils;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.TestLogger;
import org.apache.flink.util.function.RunnableWithException;

import org.apache.flink.shaded.guava18.com.google.common.collect.ImmutableMap;

import org.hamcrest.Matcher;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.flink.core.testutils.CommonTestUtils.assertThrows;
import static org.apache.flink.table.client.gateway.local.ExecutionContextTest.CATALOGS_ENVIRONMENT_FILE;
import static org.apache.flink.table.client.gateway.local.ExecutionContextTest.MODULES_ENVIRONMENT_FILE;
import static org.apache.flink.table.client.gateway.local.ExecutionContextTest.createModuleReplaceVars;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Contains basic tests for the {@link LocalExecutor}. */
@RunWith(Parameterized.class)
public class LocalExecutorITCase extends TestLogger {

    @Parameters(name = "Planner: {0}")
    public static List<String> planner() {
        return Arrays.asList(
                ExecutionEntry.EXECUTION_PLANNER_VALUE_OLD,
                ExecutionEntry.EXECUTION_PLANNER_VALUE_BLINK);
    }

    private static final String DEFAULTS_ENVIRONMENT_FILE = "test-sql-client-defaults.yaml";
    private static final String DIALECT_ENVIRONMENT_FILE = "test-sql-client-dialect.yaml";

    private static final int NUM_TMS = 2;
    private static final int NUM_SLOTS_PER_TM = 2;

    @ClassRule public static TemporaryFolder tempFolder = new TemporaryFolder();

    @ClassRule
    public static final MiniClusterWithClientResource MINI_CLUSTER_RESOURCE =
            new MiniClusterWithClientResource(
                    new MiniClusterResourceConfiguration.Builder()
                            .setConfiguration(getConfig())
                            .setNumberTaskManagers(NUM_TMS)
                            .setNumberSlotsPerTaskManager(NUM_SLOTS_PER_TM)
                            .build());

    private static ClusterClient<?> clusterClient;

    // a generated UDF jar used for testing classloading of dependencies
    private static URL udfDependency;

    @BeforeClass
    public static void setup() throws IOException {
        clusterClient = MINI_CLUSTER_RESOURCE.getClusterClient();
        File udfJar =
                TestUserClassLoaderJar.createJarFile(
                        tempFolder.newFolder("test-jar"), "test-classloader-udf.jar");
        udfDependency = udfJar.toURI().toURL();
    }

    private static Configuration getConfig() {
        Configuration config = new Configuration();
        config.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.parse("4m"));
        config.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, NUM_TMS);
        config.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, NUM_SLOTS_PER_TM);
        config.setBoolean(WebOptions.SUBMIT_ENABLE, false);
        return config;
    }

    @Parameter public String planner;

    @Rule public ExpectedException exception = ExpectedException.none();

    @Test
    public void testViews() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeSql(
                sessionId, "CREATE TEMPORARY VIEW IF NOT EXISTS AdditionalView1 AS SELECT 1");
        try {
            executor.executeSql(sessionId, "CREATE TEMPORARY VIEW AdditionalView1 AS SELECT 2");
            fail("unexpected exception");
        } catch (Exception var1) {
            assertThat(
                    var1.getCause().getMessage(),
                    is(
                            "Temporary table '`default_catalog`.`default_database`.`AdditionalView1`' already exists"));
        }
        executor.executeSql(sessionId, "CREATE VIEW AdditionalView1 AS SELECT 2");
        executor.executeSql(
                sessionId,
                "CREATE TEMPORARY VIEW IF NOT EXISTS AdditionalView2 AS SELECT * FROM AdditionalView1");

        assertShowResult(
                executor.executeSql(sessionId, "SHOW TABLES"),
                Arrays.asList(
                        "AdditionalView1",
                        "AdditionalView2",
                        "TableNumber1",
                        "TableNumber2",
                        "TableSourceSink",
                        "TestView1",
                        "TestView2"));

        // Although AdditionalView2 needs AdditionalView1, dropping AdditionalView1 first does not
        // throw.
        try {
            executor.executeSql(sessionId, "DROP VIEW AdditionalView1");
            fail("unexpected exception");
        } catch (Exception var1) {
            assertThat(
                    var1.getCause().getMessage(),
                    is(
                            "Temporary view with identifier '`default_catalog`.`default_database`.`AdditionalView1`' exists. "
                                    + "Drop it first before removing the permanent view."));
        }
        executor.executeSql(sessionId, "DROP TEMPORARY VIEW AdditionalView1");
        executor.executeSql(sessionId, "DROP VIEW AdditionalView1");
        executor.executeSql(sessionId, "DROP TEMPORARY VIEW AdditionalView2");

        assertShowResult(
                executor.executeSql(sessionId, "SHOW TABLES"),
                Arrays.asList(
                        "TableNumber1",
                        "TableNumber2",
                        "TableSourceSink",
                        "TestView1",
                        "TestView2"));
        executor.closeSession(sessionId);
    }

    @Test
    public void testListCatalogs() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        assertShowResult(
                executor.executeSql(sessionId, "SHOW CATALOGS"),
                Arrays.asList("catalog1", "default_catalog", "simple-catalog"));
        executor.closeSession(sessionId);
    }

    @Test
    public void testListDatabases() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        assertShowResult(
                executor.executeSql(sessionId, "SHOW DATABASES"),
                Collections.singletonList("default_database"));

        executor.closeSession(sessionId);
    }

    @Test
    public void testCreateDatabase() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeUpdate(sessionId, "create database db1");

        assertShowResult(
                executor.executeSql(sessionId, "SHOW DATABASES"),
                Arrays.asList("default_database", "db1"));
        executor.closeSession(sessionId);
    }

    @Test
    public void testDropDatabase() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeSql(sessionId, "create database db1");

        assertShowResult(
                executor.executeSql(sessionId, "SHOW DATABASES"),
                Arrays.asList("default_database", "db1"));

        executor.executeSql(sessionId, "drop database if exists db1");
        assertShowResult(
                executor.executeSql(sessionId, "SHOW DATABASES"),
                Collections.singletonList("default_database"));

        executor.closeSession(sessionId);
    }

    @Test
    public void testAlterDatabase() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeSql(
                sessionId, "create database db1 comment 'db1_comment' with ('k1' = 'v1')");

        executor.executeSql(sessionId, "alter database db1 set ('k1' = 'a', 'k2' = 'b')");

        assertShowResult(
                executor.executeSql(sessionId, "SHOW DATABASES"),
                Arrays.asList("default_database", "db1"));
        // todo: we should compare the new db1 properties after we support describe database in
        // LocalExecutor.

        executor.closeSession(sessionId);
    }

    @Test
    public void testAlterTable() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);
        executor.executeSql(sessionId, "use catalog `simple-catalog`");
        executor.executeSql(sessionId, "use default_database");
        assertShowResult(
                executor.executeSql(sessionId, "SHOW TABLES"),
                Collections.singletonList("test-table"));
        executor.executeSql(sessionId, "alter table `test-table` rename to t1");
        assertShowResult(
                executor.executeSql(sessionId, "SHOW TABLES"), Collections.singletonList("t1"));
        executor.closeSession(sessionId);
    }

    @Test
    public void testListTables() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        assertShowResult(
                executor.executeSql(sessionId, "SHOW TABLES"),
                Arrays.asList(
                        "TableNumber1",
                        "TableNumber2",
                        "TableSourceSink",
                        "TestView1",
                        "TestView2"));
        executor.closeSession(sessionId);
    }

    @Test
    public void testListUserDefinedFunctions() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        assertShowResult(
                executor.executeSql(sessionId, "SHOW FUNCTIONS"),
                hasItems("aggregateudf", "tableudf", "scalarudf"));
        executor.closeSession(sessionId);
    }

    @Test
    public void testSetSessionProperties() throws Exception {
        final LocalExecutor executor = createDefaultExecutor(clusterClient);
        String key = OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY.key();

        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        // check the config in Environment
        assertNull(executor.getSessionProperties(sessionId).get(key));
        // check the config in TableConfig
        assertNull(
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getConfiguration()
                        .getString(key, null));

        // modify config
        executor.setSessionProperty(sessionId, key, "ONE_PHASE");
        // check the config in Environment again
        assertEquals("ONE_PHASE", executor.getSessionProperties(sessionId).get(key));
        // check the config in TableConfig again
        assertEquals(
                "ONE_PHASE",
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getConfiguration()
                        .getString(key, null));

        // reset all properties
        executor.resetSessionProperties(sessionId);
        // check the config in Environment
        assertNull(executor.getSessionProperties(sessionId).get(key));
        // check the config in TableConfig
        assertNull(
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getConfiguration()
                        .getString(key, null));
    }

    @Test
    public void testGetSessionProperties() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);

        final SessionContext session = new SessionContext("test-session", new Environment());
        session.getSessionEnv().setExecution(ImmutableMap.of("result-mode", "changelog"));
        // Open the session and get the sessionId.
        String sessionId = executor.openSession(session);
        try {
            assertEquals("test-session", sessionId);
            assertEquals(
                    executor.getSessionProperties(sessionId).get("execution.result-mode"),
                    "changelog");

            // modify defaults
            executor.setSessionProperty(sessionId, "execution.result-mode", "table");

            final Map<String, String> actualProperties = executor.getSessionProperties(sessionId);

            final Map<String, String> expectedProperties = new HashMap<>();
            expectedProperties.put("execution.planner", planner);
            expectedProperties.put("execution.type", "batch");
            expectedProperties.put("execution.time-characteristic", "event-time");
            expectedProperties.put("execution.periodic-watermarks-interval", "99");
            expectedProperties.put("execution.parallelism", "1");
            expectedProperties.put("execution.max-parallelism", "16");
            expectedProperties.put("execution.max-idle-state-retention", "600000");
            expectedProperties.put("execution.min-idle-state-retention", "1000");
            expectedProperties.put("execution.result-mode", "table");
            expectedProperties.put("execution.max-table-result-rows", "100");
            expectedProperties.put("execution.restart-strategy.type", "failure-rate");
            expectedProperties.put("execution.restart-strategy.max-failures-per-interval", "10");
            expectedProperties.put("execution.restart-strategy.failure-rate-interval", "99000");
            expectedProperties.put("execution.restart-strategy.delay", "1000");
            expectedProperties.put("table.optimizer.join-reorder-enabled", "false");

            assertEquals(expectedProperties, actualProperties);

            // Reset session properties
            executor.resetSessionProperties(sessionId);
            assertEquals(
                    executor.getSessionProperties(sessionId).get("execution.result-mode"),
                    "changelog");
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testTableSchema() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        TableResult tableResult = executor.executeSql(sessionId, "DESCRIBE TableNumber2");
        assertEquals(
                TableSchema.builder()
                        .fields(
                                new String[] {"name", "type", "null", "key", "extras", "watermark"},
                                new DataType[] {
                                    DataTypes.STRING(),
                                    DataTypes.STRING(),
                                    DataTypes.BOOLEAN(),
                                    DataTypes.STRING(),
                                    DataTypes.STRING(),
                                    DataTypes.STRING()
                                })
                        .build(),
                tableResult.getTableSchema());
        List<Row> schemaData =
                Arrays.asList(
                        Row.of("IntegerField2", "INT", true, null, null, null),
                        Row.of("StringField2", "STRING", true, null, null, null),
                        Row.of("TimestampField2", "TIMESTAMP(3)", true, null, null, null));
        assertEquals(schemaData, CollectionUtil.iteratorToList(tableResult.collect()));
        executor.closeSession(sessionId);
    }

    @Test
    public void testCompleteStatement() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        final List<String> expectedTableHints =
                Arrays.asList(
                        "default_catalog.default_database.TableNumber1",
                        "default_catalog.default_database.TableNumber2",
                        "default_catalog.default_database.TableSourceSink");
        assertEquals(
                expectedTableHints, executor.completeStatement(sessionId, "SELECT * FROM Ta", 16));

        final List<String> expectedClause = Collections.singletonList("WHERE");
        assertEquals(
                expectedClause,
                executor.completeStatement(sessionId, "SELECT * FROM TableNumber2 WH", 29));

        final List<String> expectedField = Arrays.asList("IntegerField1");
        assertEquals(
                expectedField,
                executor.completeStatement(sessionId, "SELECT * FROM TableNumber1 WHERE Inte", 37));
        executor.closeSession(sessionId);
    }

    @Test(timeout = 90_000L)
    public void testStreamQueryExecutionChangelog() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "changelog");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        try {
            // start job and retrieval
            final ResultDescriptor desc =
                    executor.executeQuery(
                            sessionId,
                            "SELECT scalarUDF(IntegerField1), StringField1, 'ABC' FROM TableNumber1");

            assertFalse(desc.isMaterialized());

            final List<String> actualResults =
                    retrieveChangelogResult(executor, sessionId, desc.getResultId());

            final List<String> expectedResults = new ArrayList<>();
            expectedResults.add("+I[47, Hello World, ABC]");
            expectedResults.add("+I[27, Hello World, ABC]");
            expectedResults.add("+I[37, Hello World, ABC]");
            expectedResults.add("+I[37, Hello World, ABC]");
            expectedResults.add("+I[47, Hello World, ABC]");
            expectedResults.add("+I[57, Hello World!!!!, ABC]");

            TestBaseUtils.compareResultCollections(
                    expectedResults, actualResults, Comparator.naturalOrder());
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test(timeout = 90_000L)
    public void testStreamQueryExecutionChangelogMultipleTimes() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "changelog");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        final List<String> expectedResults = new ArrayList<>();
        expectedResults.add("+I[47, Hello World]");
        expectedResults.add("+I[27, Hello World]");
        expectedResults.add("+I[37, Hello World]");
        expectedResults.add("+I[37, Hello World]");
        expectedResults.add("+I[47, Hello World]");
        expectedResults.add("+I[57, Hello World!!!!]");

        try {
            for (int i = 0; i < 3; i++) {
                // start job and retrieval
                final ResultDescriptor desc =
                        executor.executeQuery(
                                sessionId,
                                "SELECT scalarUDF(IntegerField1), StringField1 FROM TableNumber1");

                assertFalse(desc.isMaterialized());

                final List<String> actualResults =
                        retrieveChangelogResult(executor, sessionId, desc.getResultId());

                TestBaseUtils.compareResultCollections(
                        expectedResults, actualResults, Comparator.naturalOrder());
            }
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test(timeout = 90_000L)
    public void testStreamQueryExecutionTable() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);

        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final String query =
                "SELECT scalarUDF(IntegerField1), StringField1, 'ABC' FROM TableNumber1";

        final List<String> expectedResults = new ArrayList<>();
        expectedResults.add("+I[47, Hello World, ABC]");
        expectedResults.add("+I[27, Hello World, ABC]");
        expectedResults.add("+I[37, Hello World, ABC]");
        expectedResults.add("+I[37, Hello World, ABC]");
        expectedResults.add("+I[47, Hello World, ABC]");
        expectedResults.add("+I[57, Hello World!!!!, ABC]");

        executeStreamQueryTable(replaceVars, query, expectedResults);
    }

    @Test(timeout = 90_000L)
    public void testStreamQueryExecutionTableMultipleTimes() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);

        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final String query = "SELECT scalarUDF(IntegerField1), StringField1 FROM TableNumber1";

        final List<String> expectedResults = new ArrayList<>();
        expectedResults.add("+I[47, Hello World]");
        expectedResults.add("+I[27, Hello World]");
        expectedResults.add("+I[37, Hello World]");
        expectedResults.add("+I[37, Hello World]");
        expectedResults.add("+I[47, Hello World]");
        expectedResults.add("+I[57, Hello World!!!!]");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        try {
            for (int i = 0; i < 3; i++) {
                executeStreamQueryTable(replaceVars, query, expectedResults);
            }
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test(timeout = 90_000L)
    public void testStreamQueryExecutionLimitedTable() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);

        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "1");

        final String query =
                "SELECT COUNT(*), StringField1 FROM TableNumber1 GROUP BY StringField1";

        final List<String> expectedResults = new ArrayList<>();
        expectedResults.add("+I[1, Hello World!!!!]");

        executeStreamQueryTable(replaceVars, query, expectedResults);
    }

    @Test(timeout = 90_000L)
    public void testBatchQueryExecution() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        try {
            final ResultDescriptor desc =
                    executor.executeQuery(sessionId, "SELECT *, 'ABC' FROM TestView1");

            assertTrue(desc.isMaterialized());

            final List<String> actualResults =
                    retrieveTableResult(executor, sessionId, desc.getResultId());

            final List<String> expectedResults = new ArrayList<>();
            expectedResults.add("+I[47, ABC]");
            expectedResults.add("+I[27, ABC]");
            expectedResults.add("+I[37, ABC]");
            expectedResults.add("+I[37, ABC]");
            expectedResults.add("+I[47, ABC]");
            expectedResults.add("+I[57, ABC]");

            TestBaseUtils.compareResultCollections(
                    expectedResults, actualResults, Comparator.naturalOrder());
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test(timeout = 90_000L)
    public void testBatchQueryExecutionMultipleTimes() throws Exception {
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        final List<String> expectedResults = new ArrayList<>();
        expectedResults.add("+I[47]");
        expectedResults.add("+I[27]");
        expectedResults.add("+I[37]");
        expectedResults.add("+I[37]");
        expectedResults.add("+I[47]");
        expectedResults.add("+I[57]");

        try {
            for (int i = 0; i < 3; i++) {
                final ResultDescriptor desc =
                        executor.executeQuery(sessionId, "SELECT * FROM TestView1");

                assertTrue(desc.isMaterialized());

                final List<String> actualResults =
                        retrieveTableResult(executor, sessionId, desc.getResultId());

                TestBaseUtils.compareResultCollections(
                        expectedResults, actualResults, Comparator.naturalOrder());
            }
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test(timeout = 90_000L)
    public void ensureExceptionOnFaultySourceInStreamingChangelogMode() throws Exception {
        final String missingFileName = "missing-source";

        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", "missing-source");
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "changelog");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESTART_STRATEGY_TYPE", "none");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        Optional<Throwable> throwableWithMessage = Optional.empty();
        try {
            final ResultDescriptor desc =
                    executor.executeQuery(sessionId, "SELECT * FROM TestView1");
            retrieveChangelogResult(executor, sessionId, desc.getResultId());
        } catch (SqlExecutionException e) {
            throwableWithMessage = findMissingFileException(e, missingFileName);
        } finally {
            executor.closeSession(sessionId);
        }
        assertTrue(throwableWithMessage.isPresent());
    }

    @Test(timeout = 90_000L)
    public void ensureExceptionOnFaultySourceInStreamingTableMode() throws Exception {
        final String missingFileName = "missing-source";

        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", missingFileName);
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "1");
        replaceVars.put("$VAR_RESTART_STRATEGY_TYPE", "none");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        Optional<Throwable> throwableWithMessage = Optional.empty();
        try {
            final ResultDescriptor desc =
                    executor.executeQuery(sessionId, "SELECT * FROM TestView1");
            retrieveTableResult(executor, sessionId, desc.getResultId());
        } catch (SqlExecutionException e) {
            throwableWithMessage = findMissingFileException(e, missingFileName);
        } finally {
            executor.closeSession(sessionId);
        }
        assertTrue(throwableWithMessage.isPresent());
    }

    @Test(timeout = 90_000L)
    public void ensureExceptionOnFaultySourceInBatch() throws Exception {
        final String missingFileName = "missing-source";

        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", missingFileName);
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        replaceVars.put("$VAR_UPDATE_MODE", "");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESTART_STRATEGY_TYPE", "none");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        Optional<Throwable> throwableWithMessage = Optional.empty();
        try {
            final ResultDescriptor desc =
                    executor.executeQuery(sessionId, "SELECT * FROM TestView1");
            retrieveTableResult(executor, sessionId, desc.getResultId());
        } catch (SqlExecutionException e) {
            throwableWithMessage = findMissingFileException(e, missingFileName);
        } finally {
            executor.closeSession(sessionId);
        }
        assertTrue(throwableWithMessage.isPresent());
    }

    private Optional<Throwable> findMissingFileException(SqlExecutionException e, String filename) {
        Optional<Throwable> throwableWithMessage;

        // for "batch" sources
        throwableWithMessage =
                ExceptionUtils.findThrowableWithMessage(
                        e, "File " + filename + " does not exist or the user running Flink");

        if (!throwableWithMessage.isPresent()) {
            // for "streaming" sources (the Blink runner always uses a streaming source
            throwableWithMessage =
                    ExceptionUtils.findThrowableWithMessage(
                            e, "The provided file path " + filename + " does not exist");
        }
        return throwableWithMessage;
    }

    @Test(timeout = 90_000L)
    public void testStreamQueryExecutionSink() throws Exception {
        final String csvOutputPath =
                new File(tempFolder.newFolder().getAbsolutePath(), "test-out.csv")
                        .toURI()
                        .toString();
        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_SOURCE_SINK_PATH", csvOutputPath);
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        try {
            executor.executeSql(sessionId, "CREATE FUNCTION LowerUDF AS 'LowerUDF'");
            // Case 1: Registered sink
            // Case 1.1: Registered sink with uppercase insert into keyword.
            // FLINK-18302: wrong classloader when INSERT INTO with UDF
            final String statement1 =
                    "INSERT INTO TableSourceSink SELECT IntegerField1 = 42,"
                            + " LowerUDF(StringField1), TimestampField1 FROM TableNumber1";
            executeAndVerifySinkResult(
                    executor, sessionId, statement1, () -> verifySinkResult(csvOutputPath));
            // Case 1.2: Registered sink with lowercase insert into keyword.
            final String statement2 =
                    "insert Into TableSourceSink \n "
                            + "SELECT IntegerField1 = 42, LowerUDF(StringField1), TimestampField1 "
                            + "FROM TableNumber1";
            executeAndVerifySinkResult(
                    executor, sessionId, statement2, () -> verifySinkResult(csvOutputPath));
            // Case 1.3: Execute the same statement again, the results should expect to be the same.
            executeAndVerifySinkResult(
                    executor, sessionId, statement2, () -> verifySinkResult(csvOutputPath));

            // Case 2: Temporary sink
            executor.executeSql(sessionId, "use catalog `simple-catalog`");
            executor.executeSql(sessionId, "use default_database");
            // create temporary sink
            executor.executeSql(
                    sessionId,
                    "CREATE TEMPORARY TABLE MySink (id int, str VARCHAR) WITH ('connector' = 'COLLECTION')");
            final String statement3 = "INSERT INTO MySink select * from `test-table`";

            // all queries are pipelined to an in-memory sink, check it is properly registered
            executeAndVerifySinkResult(
                    executor,
                    sessionId,
                    statement3,
                    () -> {
                        TestBaseUtils.compareResultCollections(
                                SimpleCatalogFactory.TABLE_CONTENTS.stream()
                                        .map(Row::toString)
                                        .collect(Collectors.toList()),
                                TestCollectionTableFactory.getResult().stream()
                                        .map(Row::toString)
                                        .collect(Collectors.toList()),
                                Comparator.naturalOrder());
                    });
        } finally {
            executor.closeSession(sessionId);
            TestCollectionTableFactory.reset();
        }
    }

    @Test
    public void testUseCatalogAndUseDatabase() throws Exception {
        final String csvOutputPath =
                new File(tempFolder.newFolder().getAbsolutePath(), "test-out.csv")
                        .toURI()
                        .toString();
        final URL url1 = getClass().getClassLoader().getResource("test-data.csv");
        final URL url2 = getClass().getClassLoader().getResource("test-data-1.csv");
        Objects.requireNonNull(url1);
        Objects.requireNonNull(url2);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url1.getPath());
        replaceVars.put("$VAR_SOURCE_PATH2", url2.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_SOURCE_SINK_PATH", csvOutputPath);
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");

        final Executor executor =
                createModifiedExecutor(CATALOGS_ENVIRONMENT_FILE, clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        try {
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW DATABASES"),
                    Collections.singletonList("mydatabase"));

            executor.executeSql(sessionId, "use catalog hivecatalog");

            assertShowResult(
                    executor.executeSql(sessionId, "SHOW CURRENT CATALOG"),
                    Collections.singletonList("hivecatalog"));

            assertShowResult(
                    executor.executeSql(sessionId, "SHOW DATABASES"),
                    Arrays.asList(
                            DependencyTest.TestHiveCatalogFactory.ADDITIONAL_TEST_DATABASE,
                            HiveCatalog.DEFAULT_DB));

            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList(
                            DependencyTest.TestHiveCatalogFactory.TABLE_WITH_PARAMETERIZED_TYPES));

            executor.executeSql(
                    sessionId,
                    "use " + DependencyTest.TestHiveCatalogFactory.ADDITIONAL_TEST_DATABASE);

            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList(DependencyTest.TestHiveCatalogFactory.TEST_TABLE));

            assertShowResult(
                    executor.executeSql(sessionId, "SHOW CURRENT DATABASE"),
                    Collections.singletonList(
                            DependencyTest.TestHiveCatalogFactory.ADDITIONAL_TEST_DATABASE));

        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testUseNonExistingDatabase() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        exception.expect(SqlExecutionException.class);
        executor.executeSql(sessionId, "use nonexistingdb");
    }

    @Test
    public void testUseNonExistingCatalog() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        exception.expect(SqlExecutionException.class);
        executor.executeSql(sessionId, "use catalog nonexistingcatalog");
        executor.closeSession(sessionId);
    }

    @Test
    public void testParameterizedTypes() throws Exception {
        // only blink planner supports parameterized types
        Assume.assumeTrue(planner.equals("blink"));
        final URL url1 = getClass().getClassLoader().getResource("test-data.csv");
        final URL url2 = getClass().getClassLoader().getResource("test-data-1.csv");
        Objects.requireNonNull(url1);
        Objects.requireNonNull(url2);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url1.getPath());
        replaceVars.put("$VAR_SOURCE_PATH2", url2.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESULT_MODE", "table");

        final Executor executor =
                createModifiedExecutor(CATALOGS_ENVIRONMENT_FILE, clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeSql(sessionId, "use catalog hivecatalog");
        String resultID =
                executor.executeQuery(
                                sessionId,
                                "select * from "
                                        + DependencyTest.TestHiveCatalogFactory
                                                .TABLE_WITH_PARAMETERIZED_TYPES)
                        .getResultId();
        retrieveTableResult(executor, sessionId, resultID);

        // make sure legacy types still work
        executor.executeSql(sessionId, "use catalog default_catalog");
        resultID = executor.executeQuery(sessionId, "select * from TableNumber3").getResultId();
        retrieveTableResult(executor, sessionId, resultID);
        executor.closeSession(sessionId);
    }

    @Test
    public void testCreateTable() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        final String ddlTemplate =
                "create table %s(\n"
                        + "  a int,\n"
                        + "  b bigint,\n"
                        + "  c varchar\n"
                        + ") with (\n"
                        + "  'connector.type'='filesystem',\n"
                        + "  'format.type'='csv',\n"
                        + "  'connector.path'='xxx'\n"
                        + ")\n";
        try {
            // Test create table with simple name.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));

            // Test create table with full qualified name.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(
                    sessionId,
                    String.format(ddlTemplate, "`simple-catalog`.`default_database`.MyTable3"));
            executor.executeSql(
                    sessionId,
                    String.format(ddlTemplate, "`simple-catalog`.`default_database`.MyTable4"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));
            executor.executeSql(sessionId, "use catalog `simple-catalog`");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable3", "MyTable4", "test-table"));

            // Test create table with db and table name.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(sessionId, String.format(ddlTemplate, "`default`.MyTable5"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "`default`.MyTable6"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2", "MyTable5", "MyTable6"));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testCreateTableIfNotExists() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        final String ddlTemplate =
                "create table if not exists %s(\n"
                        + "  a int,\n"
                        + "  b bigint,\n"
                        + "  c varchar\n"
                        + ") with (\n"
                        + "  'connector.type'='filesystem',\n"
                        + "  'format.type'='csv',\n"
                        + "  'connector.path'='xxx'\n"
                        + ")\n";
        try {
            // Test create table twice.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));

            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testCreateTableWithComputedColumn() throws Exception {
        // only blink planner support computed column for DDL
        Assume.assumeTrue(planner.equals("blink"));
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", "file:///fakePath1");
        replaceVars.put("$VAR_SOURCE_PATH2", "file:///fakePath2");
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final String ddlTemplate =
                "create table %s(\n"
                        + "  a int,\n"
                        + "  b bigint,\n"
                        + "  c as a + 1\n"
                        + ") with (\n"
                        + "  'connector.type'='filesystem',\n"
                        + "  'format.type'='csv',\n"
                        + "  'connector.path'='xxx'\n"
                        + ")\n";
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        try {
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testCreateTableWithWatermark() throws Exception {
        // only blink planner supports watermark expression for DDL
        Assume.assumeTrue(planner.equals("blink"));
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", "file:///fakePath1");
        replaceVars.put("$VAR_SOURCE_PATH2", "file:///fakePath2");
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESULT_MODE", "table");
        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final String ddlTemplate =
                "create table %s(\n"
                        + "  a int,\n"
                        + "  b timestamp(3),\n"
                        + "  watermark for b as b - INTERVAL '5' second\n"
                        + ") with (\n"
                        + "  'connector.type'='filesystem',\n"
                        + "  'format.type'='csv',\n"
                        + "  'connector.path'='xxx'\n"
                        + ")\n";
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        try {
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testCreateTableWithPropertiesChanged() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        try {
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.setSessionProperty(sessionId, "execution.type", "batch");
            final String ddlTemplate =
                    "create table %s(\n"
                            + "  a int,\n"
                            + "  b bigint,\n"
                            + "  c varchar\n"
                            + ") with (\n"
                            + "  'connector.type'='filesystem',\n"
                            + "  'format.type'='csv',\n"
                            + "  'connector.path'='xxx',\n"
                            + "  'update-mode'='append'\n"
                            + ")\n";
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            // Change the session property to trigger `new ExecutionContext`.
            executor.setSessionProperty(
                    sessionId, "execution.restart-strategy.failure-rate-interval", "12345");
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));

            // Reset the session properties.
            executor.resetSessionProperties(sessionId);
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable3"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2", "MyTable3"));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testDropTable() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        try {
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.setSessionProperty(sessionId, "execution.type", "batch");
            final String ddlTemplate =
                    "create table %s(\n"
                            + "  a int,\n"
                            + "  b bigint,\n"
                            + "  c varchar\n"
                            + ") with (\n"
                            + "  'connector.type'='filesystem',\n"
                            + "  'format.type'='csv',\n"
                            + "  'connector.path'='xxx',\n"
                            + "  'update-mode'='append'\n"
                            + ")\n";
            // Test drop table.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, "DROP TABLE MyTable1");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"), Collections.emptyList());

            // Test drop table if exists.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, "DROP TABLE IF EXISTS MyTable1");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"), Collections.emptyList());

            // Test drop table with full qualified name.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, "DROP TABLE catalog1.`default`.MyTable1");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"), Collections.emptyList());

            // Test drop table with db and table name.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, "DROP TABLE `default`.MyTable1");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"), Collections.emptyList());

            // Test drop table that does not exist.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, "DROP TABLE IF EXISTS catalog2.`default`.MyTable1");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Collections.singletonList("MyTable1"));
            executor.executeSql(sessionId, "DROP TABLE `default`.MyTable1");

            // Test drop table with properties changed.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            // Change the session property to trigger `new ExecutionContext`.
            executor.setSessionProperty(
                    sessionId, "execution.restart-strategy.failure-rate-interval", "12345");
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2"));
            executor.executeSql(sessionId, "DROP TABLE MyTable1");
            executor.executeSql(sessionId, "DROP TABLE MyTable2");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"), Collections.emptyList());

            // Test drop table with properties reset.
            // Reset the session properties.
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable1"));
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable2"));
            executor.resetSessionProperties(sessionId);
            executor.executeSql(sessionId, String.format(ddlTemplate, "MyTable3"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"),
                    Arrays.asList("MyTable1", "MyTable2", "MyTable3"));
            executor.executeSql(sessionId, "DROP TABLE MyTable1");
            executor.executeSql(sessionId, "DROP TABLE MyTable2");
            executor.executeSql(sessionId, "DROP TABLE MyTable3");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW TABLES"), Collections.emptyList());
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testCreateFunction() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        // arguments: [TEMPORARY|TEMPORARY SYSTEM], [IF NOT EXISTS], func_name
        final String ddlTemplate =
                "create %s function %s %s \n"
                        + "as 'org.apache.flink.table.client.gateway.local.LocalExecutorITCase$TestScalaFunction' LANGUAGE JAVA";
        try {
            // Test create table with simple name.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(sessionId, String.format(ddlTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(
                    sessionId, String.format(ddlTemplate, "TEMPORARY", "IF NOT EXISTS", "func2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1", "func2"));

            // Test create function with full qualified name.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(
                    sessionId,
                    String.format(
                            ddlTemplate, "", "", "`simple-catalog`.`default_database`.func3"));
            executor.executeSql(
                    sessionId,
                    String.format(
                            ddlTemplate,
                            "TEMPORARY",
                            "IF NOT EXISTS",
                            "`simple-catalog`.`default_database`.func4"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1", "func2"));
            executor.executeSql(sessionId, "use catalog `simple-catalog`");
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func3", "func4"));

            // Test create function with db and table name.
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.executeSql(
                    sessionId, String.format(ddlTemplate, "TEMPORARY", "", "`default`.func5"));
            executor.executeSql(
                    sessionId, String.format(ddlTemplate, "TEMPORARY", "", "`default`.func6"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"),
                    hasItems("func1", "func2", "func5", "func6"));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testDropFunction() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        try {
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.setSessionProperty(sessionId, "execution.type", "batch");
            // arguments: [TEMPORARY|TEMPORARY SYSTEM], [IF NOT EXISTS], func_name
            final String createTemplate =
                    "create %s function %s %s \n"
                            + "as 'org.apache.flink.table.client.gateway.local.LocalExecutorITCase$TestScalaFunction' LANGUAGE JAVA";
            // arguments: [TEMPORARY|TEMPORARY SYSTEM], [IF EXISTS], func_name
            final String dropTemplate = "drop %s function %s %s";
            // Test drop function.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "func1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), not(hasItems("func1")));

            // Test drop function if exists.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "IF EXISTS", "func1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), not(hasItems("func1")));

            // Test drop function with full qualified name.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(
                    sessionId,
                    String.format(dropTemplate, "", "IF EXISTS", "catalog1.`default`.func1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), not(hasItems("func1")));

            // Test drop function with db and function name.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(
                    sessionId, String.format(dropTemplate, "", "IF EXISTS", "`default`.func1"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), not(hasItems("func1")));

            // Test drop function that does not exist.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            try {
                executor.executeSql(
                        sessionId,
                        String.format(dropTemplate, "", "IF EXISTS", "catalog2.`default`.func1"));
                fail("unexpected");
            } catch (Exception e) {
                assertThat(e.getCause().getMessage(), is("Catalog catalog2 does not exist"));
            }
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "`default`.func1"));

            // Test drop function with properties changed.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            // Change the session property to trigger `new ExecutionContext`.
            executor.setSessionProperty(
                    sessionId, "execution.restart-strategy.failure-rate-interval", "12345");
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1", "func2"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "func1"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "func2"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"),
                    not(hasItems("func1", "func2")));

            // Test drop function with properties reset.
            // Reset the session properties.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func2"));
            executor.resetSessionProperties(sessionId);
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func3"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"),
                    hasItems("func1", "func2", "func3"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "func1"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "func2"));
            executor.executeSql(sessionId, String.format(dropTemplate, "", "", "func3"));
            assertShowResult(
                    executor.executeSql(sessionId, "SHOW FUNCTIONS"),
                    not(hasItems("func1", "func2", "func3")));
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testAlterFunction() throws Exception {
        final Executor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        try {
            executor.executeSql(sessionId, "use catalog catalog1");
            executor.setSessionProperty(sessionId, "execution.type", "batch");
            // arguments: [TEMPORARY|TEMPORARY SYSTEM], [IF NOT EXISTS], func_name
            final String createTemplate =
                    "create %s function %s %s \n"
                            + "as 'org.apache.flink.table.client.gateway.local.LocalExecutorITCase$TestScalaFunction' LANGUAGE JAVA";
            // arguments: [TEMPORARY|TEMPORARY SYSTEM], [IF EXISTS], func_name, func_class
            final String alterTemplate = "alter %s function %s %s AS %s";
            // Test alter function.
            executor.executeSql(sessionId, String.format(createTemplate, "", "", "func1"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));
            executor.executeSql(
                    sessionId,
                    String.format(alterTemplate, "", "IF EXISTS", "`default`.func1", "'newClass'"));
            assertShowResult(executor.executeSql(sessionId, "SHOW FUNCTIONS"), hasItems("func1"));

            // Test alter non temporary function with TEMPORARY keyword.
            try {
                executor.executeSql(
                        sessionId,
                        String.format(
                                alterTemplate,
                                "TEMPORARY",
                                "IF EXISTS",
                                "`default`.func2",
                                "'func3'"));
                fail("unexpected exception");
            } catch (Exception var1) {
                assertThat(
                        var1.getCause().getMessage(),
                        is("Alter temporary catalog function is not supported"));
            }
        } finally {
            executor.closeSession(sessionId);
        }
    }

    @Test
    public void testSQLDialect() throws Exception {
        LocalExecutor executor = createDefaultExecutor(clusterClient);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        // by default to use DEFAULT dialect
        assertEquals(
                SqlDialect.DEFAULT,
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getSqlDialect());
        // test switching dialect
        executor.setSessionProperty(sessionId, TableConfigOptions.TABLE_SQL_DIALECT.key(), "hive");
        assertEquals(
                SqlDialect.HIVE,
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getSqlDialect());
        executor.closeSession(sessionId);

        Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_DIALECT", "default");
        executor = createModifiedExecutor(DIALECT_ENVIRONMENT_FILE, clusterClient, replaceVars);
        sessionId = executor.openSession(session);
        assertEquals(
                SqlDialect.DEFAULT,
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getSqlDialect());
        executor.closeSession(sessionId);

        replaceVars.put("$VAR_DIALECT", "hive");
        executor = createModifiedExecutor(DIALECT_ENVIRONMENT_FILE, clusterClient, replaceVars);
        sessionId = executor.openSession(session);
        assertEquals(
                SqlDialect.HIVE,
                executor.getExecutionContext(sessionId)
                        .getTableEnvironment()
                        .getConfig()
                        .getSqlDialect());
        executor.closeSession(sessionId);
    }

    @Test
    public void testCreateFunctionWithHiveCatalog() throws Exception {
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESULT_MODE", "table");

        final Executor executor =
                createModifiedExecutor(CATALOGS_ENVIRONMENT_FILE, clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeSql(sessionId, "use catalog hivecatalog");
        executor.executeSql(sessionId, "create function lowerudf AS 'LowerUDF'");
        assertShowResult(executor.executeSql(sessionId, "show functions"), hasItems("lowerudf"));

        executor.closeSession(sessionId);
    }

    @Test
    public void testLoadModuleWithModuleConfEnabled() throws Exception {
        // only blink planner supports LOAD MODULE syntax
        Assume.assumeTrue(planner.equals("blink"));
        final Executor executor =
                createModifiedExecutor(
                        MODULES_ENVIRONMENT_FILE, clusterClient, createModuleReplaceVars());
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        assertThrows(
                "Could not execute statement: load module core",
                SqlExecutionException.class,
                () -> executor.executeSql(sessionId, "load module core"));

        executor.executeSql(sessionId, "load module hive");
        assertEquals(
                executor.listModules(sessionId),
                Arrays.asList("core", "mymodule", "myhive", "myhive2", "hive"));
    }

    @Test
    public void testUnloadModuleWithModuleConfEnabled() throws Exception {
        // only blink planner supports UNLOAD MODULE syntax
        Assume.assumeTrue(planner.equals("blink"));
        final Executor executor =
                createModifiedExecutor(
                        MODULES_ENVIRONMENT_FILE, clusterClient, createModuleReplaceVars());
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        executor.executeSql(sessionId, "unload module mymodule");
        assertEquals(executor.listModules(sessionId), Arrays.asList("core", "myhive", "myhive2"));

        exception.expect(SqlExecutionException.class);
        exception.expectMessage("Could not execute statement: unload module mymodule");
        executor.executeSql(sessionId, "unload module mymodule");
    }

    @Test
    public void testHiveBuiltInFunctionWithHiveModuleEnabled() throws Exception {
        // only blink planner supports LOAD MODULE syntax
        Assume.assumeTrue(planner.equals("blink"));

        final URL url = getClass().getClassLoader().getResource("test-data.csv");
        Objects.requireNonNull(url);
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_SOURCE_PATH1", url.getPath());
        replaceVars.put("$VAR_EXECUTION_TYPE", "streaming");
        replaceVars.put("$VAR_UPDATE_MODE", "update-mode: append");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESULT_MODE", "table");

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        // cannot use hive built-in function without loading hive module
        assertThrows(
                "Could not execute statement: select substring_index('www.apache.org', '.', 2) from TableNumber1",
                SqlExecutionException.class,
                () ->
                        executor.executeSql(
                                sessionId,
                                "select substring_index('www.apache.org', '.', 2) from TableNumber1"));

        executor.executeSql(sessionId, "load module hive");
        assertEquals(executor.listModules(sessionId), Arrays.asList("core", "hive"));

        assertShowResult(
                executor.executeSql(
                        sessionId,
                        "select substring_index('www.apache.org', '.', 2) from TableNumber1"),
                hasItems("www.apache"));
    }

    private void executeStreamQueryTable(
            Map<String, String> replaceVars, String query, List<String> expectedResults)
            throws Exception {

        final Executor executor = createModifiedExecutor(clusterClient, replaceVars);
        final SessionContext session = new SessionContext("test-session", new Environment());
        String sessionId = executor.openSession(session);
        assertEquals("test-session", sessionId);

        try {
            // start job and retrieval
            final ResultDescriptor desc = executor.executeQuery(sessionId, query);

            assertTrue(desc.isMaterialized());

            final List<String> actualResults =
                    retrieveTableResult(executor, sessionId, desc.getResultId());

            TestBaseUtils.compareResultCollections(
                    expectedResults, actualResults, Comparator.naturalOrder());
        } finally {
            executor.closeSession(sessionId);
        }
    }

    private void assertShowResult(TableResult showResult, List<String> expected) {
        List<String> actual =
                CollectionUtil.iteratorToList(showResult.collect()).stream()
                        .map(r -> checkNotNull(r.getField(0)).toString())
                        .collect(Collectors.toList());
        assertEquals(expected, actual);
    }

    private void assertShowResult(TableResult showResult, Matcher<Iterable<String>> matcher) {
        List<String> actual =
                CollectionUtil.iteratorToList(showResult.collect()).stream()
                        .map(r -> checkNotNull(r.getField(0)).toString())
                        .collect(Collectors.toList());
        assertThat(actual, matcher);
    }

    private void verifySinkResult(String path) throws IOException {
        final List<String> actualResults = new ArrayList<>();
        TestBaseUtils.readAllResultLines(actualResults, path);
        final List<String> expectedResults = new ArrayList<>();
        expectedResults.add("true,hello world,2020-01-01 00:00:01.0");
        expectedResults.add("false,hello world,2020-01-01 00:00:02.0");
        expectedResults.add("false,hello world,2020-01-01 00:00:03.0");
        expectedResults.add("false,hello world,2020-01-01 00:00:04.0");
        expectedResults.add("true,hello world,2020-01-01 00:00:05.0");
        expectedResults.add("false,hello world!!!!,2020-01-01 00:00:06.0");
        TestBaseUtils.compareResultCollections(
                expectedResults, actualResults, Comparator.naturalOrder());
    }

    private void executeAndVerifySinkResult(
            Executor executor,
            String sessionId,
            String statement,
            RunnableWithException verifyResult)
            throws Exception {
        final ProgramTargetDescriptor targetDescriptor =
                executor.executeUpdate(sessionId, statement);

        // wait for job completion and verify result
        boolean isRunning = true;
        while (isRunning) {
            Thread.sleep(50); // slow the processing down
            final JobStatus jobStatus =
                    clusterClient.getJobStatus(targetDescriptor.getJobId()).get();
            switch (jobStatus) {
                case CREATED:
                case RUNNING:
                    continue;
                case FINISHED:
                    isRunning = false;
                    verifyResult.run();
                    break;
                default:
                    fail("Unexpected job status.");
            }
        }
    }

    private <T> LocalExecutor createDefaultExecutor(ClusterClient<T> clusterClient)
            throws Exception {
        final Map<String, String> replaceVars = new HashMap<>();
        replaceVars.put("$VAR_PLANNER", planner);
        replaceVars.put("$VAR_EXECUTION_TYPE", "batch");
        replaceVars.put("$VAR_UPDATE_MODE", "");
        replaceVars.put("$VAR_MAX_ROWS", "100");
        replaceVars.put("$VAR_RESTART_STRATEGY_TYPE", "failure-rate");
        return new LocalExecutor(
                EnvironmentFileUtil.parseModified(DEFAULTS_ENVIRONMENT_FILE, replaceVars),
                Collections.emptyList(),
                clusterClient.getFlinkConfiguration(),
                new DefaultCLI(),
                new DefaultClusterClientServiceLoader());
    }

    private <T> LocalExecutor createModifiedExecutor(
            ClusterClient<T> clusterClient, Map<String, String> replaceVars) throws Exception {
        replaceVars.putIfAbsent("$VAR_RESTART_STRATEGY_TYPE", "failure-rate");
        return new LocalExecutor(
                EnvironmentFileUtil.parseModified(DEFAULTS_ENVIRONMENT_FILE, replaceVars),
                Collections.singletonList(udfDependency),
                clusterClient.getFlinkConfiguration(),
                new DefaultCLI(),
                new DefaultClusterClientServiceLoader());
    }

    private <T> LocalExecutor createModifiedExecutor(
            String yamlFile, ClusterClient<T> clusterClient, Map<String, String> replaceVars)
            throws Exception {
        replaceVars.putIfAbsent("$VAR_RESTART_STRATEGY_TYPE", "failure-rate");
        return new LocalExecutor(
                EnvironmentFileUtil.parseModified(yamlFile, replaceVars),
                Collections.singletonList(udfDependency),
                clusterClient.getFlinkConfiguration(),
                new DefaultCLI(),
                new DefaultClusterClientServiceLoader());
    }

    private List<String> retrieveTableResult(Executor executor, String sessionId, String resultID)
            throws InterruptedException {

        final List<String> actualResults = new ArrayList<>();
        while (true) {
            Thread.sleep(50); // slow the processing down
            final TypedResult<Integer> result = executor.snapshotResult(sessionId, resultID, 2);
            if (result.getType() == TypedResult.ResultType.PAYLOAD) {
                actualResults.clear();
                IntStream.rangeClosed(1, result.getPayload())
                        .forEach(
                                (page) -> {
                                    for (Row row : executor.retrieveResultPage(resultID, page)) {
                                        actualResults.add(row.toString());
                                    }
                                });
            } else if (result.getType() == TypedResult.ResultType.EOS) {
                break;
            }
        }

        return actualResults;
    }

    private List<String> retrieveChangelogResult(
            Executor executor, String sessionId, String resultID) throws InterruptedException {

        final List<String> actualResults = new ArrayList<>();
        while (true) {
            Thread.sleep(50); // slow the processing down
            final TypedResult<List<Row>> result =
                    executor.retrieveResultChanges(sessionId, resultID);
            if (result.getType() == TypedResult.ResultType.PAYLOAD) {
                for (Row row : result.getPayload()) {
                    actualResults.add(row.toString());
                }
            } else if (result.getType() == TypedResult.ResultType.EOS) {
                break;
            }
        }
        return actualResults;
    }

    // --------------------------------------------------------------------------------------------
    // Test functions
    // --------------------------------------------------------------------------------------------

    /** Scala Function for test. */
    public static class TestScalaFunction extends ScalarFunction {
        public long eval(int i, long l, String s) {
            return i + l + s.length();
        }
    }
}
