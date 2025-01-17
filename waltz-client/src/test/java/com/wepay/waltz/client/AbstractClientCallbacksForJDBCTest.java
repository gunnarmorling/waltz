package com.wepay.waltz.client;

import com.wepay.waltz.client.internal.RpcClient;
import com.wepay.waltz.client.internal.mock.MockDriver;
import com.wepay.waltz.client.internal.mock.MockServerPartition;
import com.wepay.waltz.common.message.ReqId;
import com.wepay.waltz.test.util.StringSerializer;
import com.wepay.zktools.clustermgr.Endpoint;
import com.wepay.zktools.util.Uninterruptibly;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AbstractClientCallbacksForJDBCTest {

    private static final String CLIENT_HIGH_WATER_MARK_TABLE_NAME = "TEST_CLIENT_HIGH_WATER_MARK";
    private static final int MAX_ATTEMPTS = 3;

    @Test
    public void testRetryThenSuccess() throws Exception {
        TestDataSource dataSource = new TestDataSource("TEST_RETRY_SUCC");
        Connection connection = dataSource.getConnection();
        dropSchema(connection);
        createSchema(connection);

        Map<Integer, MockServerPartition> serverPartitions = MockServerPartition.create(1);
        MockDriver mockDriver1 = new MockDriver(1, serverPartitions);
        WaltzClientConfig config1 = new WaltzClientConfig(new Properties());
        config1.setObject(WaltzClientConfig.MOCK_DRIVER, mockDriver1);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executionCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        WaltzClientCallbacks callbacks1 = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
            @Override
            protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                if (executionCount.incrementAndGet() < 3) {
                    throw new SQLException("test");
                } else {
                    latch.countDown();
                }
            }

            @Override
            public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                latch.countDown();
            }
        };

        WaltzClient client1 = new WaltzClient(callbacks1, config1);

        client1.submit(mkTransactionContext(0));

        latch.await();

        assertEquals(MAX_ATTEMPTS, executionCount.get());
        assertEquals(0, failureCount.get());
    }

    @Test
    public void testRetryThenFailure() throws Exception {
        TestDataSource dataSource = new TestDataSource("TEST_RETRY_FAIL");
        Connection connection = dataSource.getConnection();
        dropSchema(connection);
        createSchema(connection);

        Map<Integer, MockServerPartition> serverPartitions = MockServerPartition.create(1);
        MockDriver mockDriver1 = new MockDriver(1, serverPartitions);
        WaltzClientConfig config1 = new WaltzClientConfig(new Properties());
        config1.setObject(WaltzClientConfig.MOCK_DRIVER, mockDriver1);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executionCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        WaltzClientCallbacks callbacks1 = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
            @Override
            protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                if (executionCount.incrementAndGet() < MAX_ATTEMPTS) {
                    // SQLException is caught by AbstractClientCallbacksForJDBC.applyTransaction(Transaction).
                    // This method, applyTransaction(Transaction, Connection), will be retried.
                    throw new SQLException("test");

                } else if (executionCount.get() == MAX_ATTEMPTS) {
                    // This exception is not caught by AbstractClientCallbacksForJDBC.applyTransaction(Transaction),
                    // thus, uncaughtException will be called.
                    throw new RuntimeException("test");

                } else {
                    latch.countDown();
                }
            }

            @Override
            public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                failureCount.incrementAndGet();
            }
        };

        WaltzClient client1 = new WaltzClient(callbacks1, config1);

        client1.submit(mkTransactionContext(0));

        latch.await();

        assertEquals(MAX_ATTEMPTS + 1, executionCount.get());
        assertEquals(1, failureCount.get());
    }

    @Test
    public void testConcurrentClients() throws Exception {
        TestDataSource dataSource = new TestDataSource("TEST_CONCURRENT");
        Connection connection = dataSource.getConnection();
        dropSchema(connection);
        createSchema(connection);

        Map<Integer, MockServerPartition> serverPartitions = MockServerPartition.create(1);
        MockDriver mockDriver1 = new MockDriver(1, serverPartitions);
        WaltzClientConfig config1 = new WaltzClientConfig(new Properties());
        config1.setObject(WaltzClientConfig.MOCK_DRIVER, mockDriver1);

        MockDriver mockDriver2 = new MockDriver(1, serverPartitions);
        WaltzClientConfig config2 = new WaltzClientConfig(new Properties());
        config2.setObject(WaltzClientConfig.MOCK_DRIVER, mockDriver2);

        final CountDownLatch latch = new CountDownLatch(1);

        final int numTransactions = 1000;
        final ArrayList<String> resultsAll = new ArrayList<>();
        final ArrayList<String> results1 = new ArrayList<>();
        final ArrayList<String> results2 = new ArrayList<>();

        WaltzClientCallbacks callbacks1 = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
            @Override
            protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                synchronized (results1) {
                    results1.add(transaction.getTransactionData(StringSerializer.INSTANCE));
                }
                synchronized (resultsAll) {
                    resultsAll.add(transaction.getTransactionData(StringSerializer.INSTANCE));
                    if (resultsAll.size() >= numTransactions) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                latch.countDown();
            }
        };

        WaltzClientCallbacks callbacks2 = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
            @Override
            protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                synchronized (results2) {
                    results2.add(transaction.getTransactionData(StringSerializer.INSTANCE));
                }
                synchronized (resultsAll) {
                    resultsAll.add(transaction.getTransactionData(StringSerializer.INSTANCE));
                    if (resultsAll.size() >= numTransactions) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                latch.countDown();
            }
        };

        WaltzClient client1 = new WaltzClient(callbacks1, config1);
        WaltzClient client2 = new WaltzClient(callbacks2, config2);

        ArrayList<String> expectedAll = new ArrayList<>();
        for (int i = 0; i < numTransactions; i += 2) {
            client1.submit(mkTransactionContext(i));
            client2.submit(mkTransactionContext(i + 1));
            expectedAll.add(String.format("test data: %04d", i));
            expectedAll.add(String.format("test data: %04d", i + 1));
        }

        latch.await();

        Collections.sort(resultsAll);

        ArrayList<String> unioned = new ArrayList<>();
        unioned.addAll(results1);
        unioned.addAll(results2);
        Collections.sort(unioned);

        assertEquals(expectedAll, resultsAll);
        assertEquals(expectedAll, unioned);
        assertEquals(expectedAll.size(), results1.size() + results2.size());
    }

    @Test
    public void testDatabaseDown() throws Exception {
        TestDataSource dataSource = new TestDataSource("TEST_DB_DOWN");
        Connection connection = dataSource.getConnection();
        dropSchema(connection);
        createSchema(connection);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executionCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        WaltzClientCallbacks callbacks1 = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
            @Override
            protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                executionCount.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                latch.countDown();
            }
        };

        assertEquals(-1L, callbacks1.getClientHighWaterMark(0));

        dataSource.makeDbDown();

        Thread thread = new Thread(() -> {
            try {
                callbacks1.applyTransaction(mkTransaction());
            } catch (Exception ex) {
                failureCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        thread.start();

        // applyTransaction will retry until database comes back.
        int maxBadConnections = dataSource.numConnectionsMade() + 3;
        while (dataSource.numConnectionsMade() < maxBadConnections && thread.isAlive()) {
            Uninterruptibly.sleep(10);
        }

        dataSource.makeDbUp();

        latch.await();

        assertEquals(1, executionCount.get());
        assertEquals(0, failureCount.get());
        assertTrue(dataSource.numConnectionsMade() >= maxBadConnections);
    }

    @Test
    public void testOnErrorGettingClientHighWaterMark() throws Exception {
        TestDataSource dataSource = new TestDataSource("TEST_EXCEPTION_IN_GET_CLIENT_HIGH_WATER_MARK");
        Connection connection = dataSource.getConnection();
        dropSchema(connection);
        createSchema(connection);

        Map<Integer, MockServerPartition> serverPartitions = MockServerPartition.create(1);
        MockDriver mockDriver1 = new MockDriver(1, serverPartitions);
        WaltzClientConfig config1 = new WaltzClientConfig(new Properties());
        config1.setObject(WaltzClientConfig.MOCK_DRIVER, mockDriver1);

        WaltzClientCallbacks callbacks1 = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
            private int expectedAttempts = 0;

            @Override
            protected void onErrorGettingClientHighWaterMark(int attempts, SQLException exception) {
                expectedAttempts++;

                assertEquals(expectedAttempts, attempts);

                if (attempts == 5) {
                    throw new RuntimeException("no more attempts", exception);
                } else if (attempts > 5) {
                    fail();
                }
            }

            @Override
            protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                // Do nothing
            }

            @Override
            public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                // Do nothing
            }
        };

        dataSource.makeDbDown();

        try {
            new WaltzClient(callbacks1, config1);
            fail();

        } catch (RuntimeException ex) {
            assertEquals("no more attempts", ex.getMessage());
            assertTrue(ex.getCause() instanceof SQLException);
        }
    }

    @Test
    public void testForbiddenCommit() throws Exception {
        testForbiddenJdbcCalls("commit", Connection::commit);
    }

    @Test
    public void testForbiddenRollback() throws Exception {
        testForbiddenJdbcCalls("rollback", Connection::rollback);
    }

    @Test
    public void testForbiddenSetAutoCommit() throws Exception {
        testForbiddenJdbcCalls("setAutoCommit", connection -> connection.setAutoCommit(false));
    }

    @Test
    public void testForbiddenSetTransactionIsolation() throws Exception {
        int isolation = Connection.TRANSACTION_REPEATABLE_READ;
        testForbiddenJdbcCalls("setTransactionIsolation", connection -> connection.setTransactionIsolation(isolation)
        );
    }

    @Test
    public void testForbiddenSetCatalog() throws Exception {
        testForbiddenJdbcCalls("setCatalog", connection -> connection.setCatalog("another"));
    }

    @Test
    public void testForbiddenSetReadOnly() throws Exception {
        testForbiddenJdbcCalls("setReadOnly", connection -> connection.setReadOnly(true));
    }

    @Test
    public void testForbiddenUnwrap() throws Exception {
        testForbiddenJdbcCalls("unwrap", connection -> connection.unwrap(Connection.class));
    }

    private void testForbiddenJdbcCalls(String methodName, JdbcCall jdbcCall) throws Exception {
        TestDataSource dataSource = new TestDataSource("TEST_FORBIDDEN_" + methodName);
        try (Connection connection = dataSource.getConnection()) {
            dropSchema(connection);
            createSchema(connection);

            WaltzClientCallbacks callbacks = new AbstractClientCallbacksForJDBC(dataSource, CLIENT_HIGH_WATER_MARK_TABLE_NAME) {
                @Override
                protected void applyTransaction(Transaction transaction, Connection connection) throws SQLException {
                    try {
                        jdbcCall.run(connection);
                    } catch (SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                }

                @Override
                public void uncaughtException(int partitionId, long transactionId, Throwable exception) {
                }
            };

            // set up the internal state fo the callback class
            callbacks.getClientHighWaterMark(0);

            try {
                callbacks.applyTransaction(mkTransaction());
                fail();

            } catch (IllegalStateException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof AbstractClientCallbacksForJDBC.ForbiddenJdbcCallException) {
                    assertEquals(
                        methodName,
                        ((AbstractClientCallbacksForJDBC.ForbiddenJdbcCallException) cause).methodName
                    );
                } else {
                    throw ex;
                }
            }
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement(
            "CREATE TABLE " + CLIENT_HIGH_WATER_MARK_TABLE_NAME + " ("
                + " PARTITION_ID INTEGER NOT NULL,"
                + " HIGH_WATER_MARK BIGINT NOT NULL,"
                + " PRIMARY KEY (PARTITION_ID)"
                + ");"
        );
        try {
            stmt.execute();
            connection.commit();
        } catch (SQLException ex) {
            try {
                connection.rollback();
            } catch (SQLException ex2) {
                // Ignore
            }
        } finally {
            try {
                stmt.close();
            } catch (SQLException ex2) {
                // Ignore
            }

        }
    }

    private void dropSchema(Connection connection) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "DROP TABLE IF EXISTS " + CLIENT_HIGH_WATER_MARK_TABLE_NAME + ";"
            );
            try {
                stmt.execute();
                stmt.close();
                connection.commit();
            } catch (Throwable ex) {
                try {
                    connection.rollback();
                } catch (SQLException ex2) {
                    // Ignore
                }
            } finally {
                try {
                    stmt.close();
                } catch (SQLException ex2) {
                    // Ignore
                }
            }
        } catch (Throwable ex) {
            // Ignore
        }
    }

    private TransactionContext mkTransactionContext(final int seqNum) {
        return new TransactionContext() {
            @Override
            public int partitionId(int numPartitions) {
                return 0;
            }

            @Override
            public boolean execute(TransactionBuilder builder) {
                builder.setTransactionData(String.format("test data: %04d", seqNum), StringSerializer.INSTANCE);
                return true;
            }
        };
    }

    private Transaction mkTransaction() {
        RpcClient mockRpcClient = new RpcClient() {
            @Override
            public Future<byte[]> getTransactionData(int partitionId, long transactionId) {
                return CompletableFuture.completedFuture("dummy".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Future<Long> getHighWaterMark(int partitionId) {
                return CompletableFuture.completedFuture(-1L);
            }

            @Override
            public void close() {
            }

            @Override
            public CompletableFuture<Map<Endpoint, Map<String, Boolean>>> checkServerConnections(Set<Endpoint> serverEndpoints) {
                return CompletableFuture.completedFuture(new HashMap<>());
            }

            @Override
            public Future<List<Integer>> getServerPartitionAssignments(Endpoint serverEndpoint) {
                return CompletableFuture.completedFuture(new ArrayList<>());
            }

            @Override
            public CompletableFuture<Boolean> addPreferredPartition(Endpoint serverEndpoint, List<Integer> partitionIds) throws InterruptedException {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Boolean> removePreferredPartition(Endpoint serverEndpoint, List<Integer> partitionIds) throws InterruptedException {
                return CompletableFuture.completedFuture(true);
            }
        };

        return new Transaction(0, 0, new ReqId(0, 0, 0, 0), mockRpcClient);
    }

    private interface JdbcCall {
        void run(Connection conn) throws SQLException;
    }

}
