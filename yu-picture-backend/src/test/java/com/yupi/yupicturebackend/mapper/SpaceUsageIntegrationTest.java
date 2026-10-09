package com.yupi.yupicturebackend.mapper;

import com.yupi.yupicturebackend.model.entity.Space;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the configured MySQL database; each test creates and removes only its own space rows. */
@SpringBootTest
@Tag("integration")
@Timeout(45)
class SpaceUsageIntegrationTest {
    @Autowired
    private SpaceMapper spaceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Space> ownedSpaces = new ArrayList<>();

    @BeforeEach
    void requireRealMysql() {
        String product = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        assertEquals("MySQL", product, "Quota locking must be verified against the configured MySQL database");
    }

    @AfterEach
    void removeOnlyOwnedRows() {
        for (Space space : ownedSpaces) {
            // Bypass logical deletion to leave no test debris. Both generated ID and marker must match.
            assertEquals(1, jdbcTemplate.update("DELETE FROM space WHERE id = ? AND spaceName = ?",
                    space.getId(), space.getSpaceName()));
        }
    }

    @Test
    void twoConcurrentReservationsCannotBothClaimTheFinalSlot() throws Exception {
        Space space = createSpace(1000, 1, 0, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "space-usage-integration");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<Integer>> reservations = new ArrayList<>();
        try {
            Callable<Integer> reserve = () -> newTransaction().execute(status -> {
                ready.countDown();
                awaitStart(start);
                return spaceMapper.adjustUsage(space.getId(), 100, 1);
            });
            reservations.add(executor.submit(reserve));
            reservations.add(executor.submit(reserve));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both independent transactions must reach the barrier");
            start.countDown();

            int first = reservations.get(0).get(15, TimeUnit.SECONDS);
            int second = reservations.get(1).get(15, TimeUnit.SECONDS);
            assertTrue(first == 0 || first == 1);
            assertTrue(second == 0 || second == 1);
            assertEquals(1, first + second, "Exactly one guarded UPDATE must reserve the final slot");
            assertUsage(space.getId(), 100, 1);
        } finally {
            start.countDown();
            reservations.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Reservation threads must finish before cleanup");
        }
    }

    @Test
    void aRolledBackTransactionRestoresBothUsageCounters() {
        Space space = createSpace(1000, 10, 20, 1);
        IllegalStateException rollback = new IllegalStateException("Expected integration-test rollback");

        IllegalStateException actual = assertThrows(IllegalStateException.class, () ->
                newTransaction().execute(status -> {
                    assertEquals(1, spaceMapper.adjustUsage(space.getId(), 80, 1));
                    assertUsage(space.getId(), 100, 2);
                    throw rollback;
                }));

        assertSame(rollback, actual);
        assertUsage(space.getId(), 20, 1);
    }

    @Test
    void decreasesCanRecoverAnExistingOverquotaSpaceWithoutAllowingUnderflow() {
        Space space = createSpace(100, 1, 150, 3);

        assertEquals(1, spaceMapper.adjustUsage(space.getId(), -20, 0),
                "Shrinking a picture must work even while size and count remain over quota");
        assertUsage(space.getId(), 130, 3);
        assertEquals(1, spaceMapper.adjustUsage(space.getId(), 0, -1),
                "Reducing count must work even while the unchanged size remains over quota");
        assertUsage(space.getId(), 130, 2);
        assertEquals(0, spaceMapper.adjustUsage(space.getId(), 1, 0));
        assertEquals(0, spaceMapper.adjustUsage(space.getId(), 0, 1));
        assertUsage(space.getId(), 130, 2);

        assertEquals(1, spaceMapper.adjustUsage(space.getId(), -30, -1));
        assertUsage(space.getId(), 100, 1);
        assertEquals(0, spaceMapper.adjustUsage(space.getId(), -101, 0));
        assertEquals(0, spaceMapper.adjustUsage(space.getId(), 0, -2));
        assertUsage(space.getId(), 100, 1);
    }

    private Space createSpace(long maxSize, long maxCount, long totalSize, long totalCount) {
        Space space = new Space();
        // Normal application IDs are positive. A random negative ID avoids advancing AUTO_INCREMENT.
        space.setId(UUID.randomUUID().getMostSignificantBits() | Long.MIN_VALUE);
        space.setSpaceName("test-adjustUsage-" + UUID.randomUUID());
        space.setSpaceLevel(0);
        space.setSpaceType(0);
        space.setUserId(space.getId());
        space.setMaxSize(maxSize);
        space.setMaxCount(maxCount);
        space.setTotalSize(totalSize);
        space.setTotalCount(totalCount);
        space.setIsDelete(0);
        assertEquals(1, spaceMapper.insert(space));
        ownedSpaces.add(space);
        return space;
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
        return transaction;
    }

    private void assertUsage(long id, long expectedSize, long expectedCount) {
        Space stored = spaceMapper.selectById(id);
        assertNotNull(stored);
        assertEquals(Long.valueOf(expectedSize), stored.getTotalSize());
        assertEquals(Long.valueOf(expectedCount), stored.getTotalCount());
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent reservations did not start within five seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent reservation was interrupted", e);
        }
    }
}
