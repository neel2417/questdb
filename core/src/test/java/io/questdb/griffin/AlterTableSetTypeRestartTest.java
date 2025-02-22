/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.AbstractBootstrapTest;
import io.questdb.Bootstrap;
import io.questdb.PropertyKey;
import io.questdb.ServerMain;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableToken;
import io.questdb.std.str.Path;
import io.questdb.test.tools.TestUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.postgresql.util.PSQLException;

import static io.questdb.griffin.AlterTableSetTypeTest.NON_WAL;
import static io.questdb.griffin.AlterTableSetTypeTest.WAL;
import static org.junit.Assert.*;

public class AlterTableSetTypeRestartTest extends AbstractAlterTableSetTypeRestartTest {

    @BeforeClass
    public static void setUpStatic() throws Exception {
        AbstractBootstrapTest.setUpStatic();
        try {
            createDummyConfiguration(PropertyKey.CAIRO_WAL_SUPPORTED.getPropertyPath() + "=true");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testConvertLoop2() throws Exception {
        final String tableName = testName.getMethodName();
        TestUtils.assertMemoryLeak(() -> {
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();
                createTable(tableName, "WAL");

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                insertInto(tableName);
                insertInto(tableName);
                insertInto(tableName);
                drainWalQueue(engine);

                // WAL table
                assertTrue(engine.isWalTable(token));
                setType(tableName, "BYPASS WAL");
                assertNumOfRows(engine, tableName, 3);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // table has been converted to non-WAL
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 3);
                setType(tableName, "WAL");
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // table has been converted to WAL
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 3);

                insertInto(tableName);
                drainWalQueue(engine);
                assertNumOfRows(engine, tableName, 4);

                dropTable(tableName);
            }
            validateShutdown(tableName);
        });
    }

    @Test
    public void testNonPartitionedToWal() throws Exception {
        final String tableName = testName.getMethodName();
        TestUtils.assertMemoryLeak(() -> {
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();
                createNonPartitionedTable(tableName);
                insertInto(tableName);

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // non-WAL table
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 1);
                assertConvertFileDoesNotExist(engine, token);

                // table conversion to WAL is not allowed
                try {
                    setType(tableName, "WAL");
                    fail("Expected exception is missing");
                } catch (PSQLException e) {
                    TestUtils.assertContains(e.getMessage(), "Cannot convert non-partitioned table");
                }
            }
        });
    }

    @Test
    public void testNonWalToWalWithDropTable() throws Exception {
        final String tableName = testName.getMethodName();
        TestUtils.assertMemoryLeak(() -> {
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();
                createTable(tableName, "BYPASS WAL");
                insertInto(tableName);

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // non-WAL table
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 1);
                assertConvertFileDoesNotExist(engine, token);

                // schedule table conversion to WAL
                setType(tableName, "WAL");
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, WAL);

                insertInto(tableName);
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 2);

                // drop table
                dropTable(tableName);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                try {
                    engine.getTableToken(tableName);
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table does not exist [table=" + tableName + ']');
                }
            }
        });
    }

    @Test
    public void testSetType() throws Exception {
        final String tableName = testName.getMethodName();
        TestUtils.assertMemoryLeak(() -> {
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();
                createTable(tableName, "BYPASS WAL");
                insertInto(tableName);

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // non-WAL table
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 1);
                assertConvertFileDoesNotExist(engine, token);

                // schedule table conversion to WAL
                setType(tableName, "WAL");
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, WAL);

                insertInto(tableName);
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 2);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // table has been converted to WAL
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 2);
                assertConvertFileDoesNotExist(engine, token);

                insertInto(tableName);
                insertInto(tableName);
                drainWalQueue(engine);
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 4);

                // schedule table conversion back to non-WAL
                setType(tableName, "BYPASS WAL");
                drainWalQueue(engine);
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, NON_WAL);

                insertInto(tableName);
                drainWalQueue(engine);
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 5);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // table has been converted to non-WAL
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 5);
                assertConvertFileDoesNotExist(engine, token);

                insertInto(tableName);
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 6);

                // schedule table conversion to non-WAL again
                setType(tableName, "BYPASS WAL");
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, NON_WAL);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // no conversion happened, table was already non-WAL type
                assertFalse(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 6);
                assertConvertFileDoesNotExist(engine, token);

                // schedule table conversion to WAL
                setType(tableName, "WAL");
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, WAL);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                // table has been converted to WAL
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 6);
                assertConvertFileDoesNotExist(engine, token);

                insertInto(tableName);
                insertInto(tableName);
                insertInto(tableName);
                drainWalQueue(engine);
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 9);

                dropTable(tableName);
                drainWalQueue(engine);
            }
            validateShutdown(tableName);
        });
    }

    @Test
    public void testWalToNonWal() throws Exception {
        final String tableName = testName.getMethodName();
        TestUtils.assertMemoryLeak(() -> {
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();
                createTable(tableName, "WAL");

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                insertInto(tableName);
                drainWalQueue(engine);

                // WAL table
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 1);
                assertConvertFileDoesNotExist(engine, token);

                // schedule table conversion to non-WAL
                setType(tableName, "BYPASS WAL");
                drainWalQueue(engine);
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, NON_WAL);

                insertInto(tableName);
                drainWalQueue(engine);
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 2);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);
                assertFalse(engine.isWalTable(token));
                insertInto(tableName);
                assertNumOfRows(engine, tableName, 3);

                dropTable(tableName);
            }
        });
    }

    @Test
    public void testWalToNonWalWithDropTable() throws Exception {
        final String tableName = testName.getMethodName();
        TestUtils.assertMemoryLeak(() -> {
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();
                createTable(tableName, "WAL");

                final CairoEngine engine = questdb.getCairoEngine();
                final TableToken token = engine.getTableToken(tableName);

                insertInto(tableName);
                drainWalQueue(engine);

                // WAL table
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 1);
                assertConvertFileDoesNotExist(engine, token);

                // schedule table conversion to non-WAL
                setType(tableName, "BYPASS WAL");
                drainWalQueue(engine);
                final Path path = assertConvertFileExists(engine, token);
                assertConvertFileContent(path, NON_WAL);

                insertInto(tableName);
                drainWalQueue(engine);
                assertTrue(engine.isWalTable(token));
                assertNumOfRows(engine, tableName, 2);

                // drop table
                dropTable(tableName);
                drainWalQueue(engine);
            }
            validateShutdown(tableName);

            // restart
            try (final ServerMain questdb = new TestServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION)) {
                questdb.start();

                final CairoEngine engine = questdb.getCairoEngine();
                try {
                    engine.getTableToken(tableName);
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table does not exist [table=" + tableName + ']');
                }
            }
        });
    }
}
