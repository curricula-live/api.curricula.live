package live.curricula.api.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import live.curricula.api.planning.PlanningStore.Account;
import live.curricula.api.planning.PlanningStore.Draft;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class PlanningStoreTests {
    private static final String CONCEPT = "https://curricula.live/id/formal-sciences/concept/dpll-algorithm";

    @Test void databaseEnforcesOwnershipAndCompetingUpdates() throws Exception {
        String url = System.getenv("CURRICULA_TEST_POSTGRES_URL");
        Assumptions.assumeTrue(url != null, "Use a disposable PostgreSQL database");
        var source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(System.getenv("CURRICULA_TEST_POSTGRES_USER"));
        source.setPassword(System.getenv("CURRICULA_TEST_POSTGRES_PASSWORD"));
        try (var resource = getClass().getResourceAsStream("/db/planning/001-initial.sql");
             var connection = source.getConnection(); var statement = connection.createStatement()) {
            assertNotNull(resource);
            statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        var store = new PlanningStore(source);
        String subject = UUID.randomUUID().toString();
        var owner = new Account("https://school-one.example", subject);
        var outsider = new Account("https://school-two.example", subject);
        store.registerVerifiedAccount(owner);
        store.registerVerifiedAccount(owner);
        store.registerVerifiedAccount(outsider);
        var plan = store.create(owner, new Draft(" Logic lesson ", CONCEPT));
        assertEquals("Logic lesson", plan.title());
        assertEquals(CONCEPT, store.find(owner, plan.id()).orElseThrow().conceptIri());
        assertTrue(store.find(outsider, plan.id()).isEmpty());
        assertTrue(store.update(outsider, plan.id(), 1, new Draft("Replace", CONCEPT)).isEmpty());

        try (var writers = Executors.newFixedThreadPool(2)) {
            var a = writers.submit(() -> store.update(owner, plan.id(), 1, new Draft("Edit one", CONCEPT)));
            var b = writers.submit(() -> store.update(owner, plan.id(), 1, new Draft("Edit two", CONCEPT)));
            assertNotEquals(a.get(10, TimeUnit.SECONDS).isPresent(), b.get(10, TimeUnit.SECONDS).isPresent());
        }
        assertEquals(2L, store.find(owner, plan.id()).orElseThrow().revision());
        assertThrows(IllegalArgumentException.class, () -> new Draft("Fake", CONCEPT + "/../../elsewhere"));
        assertThrows(IllegalArgumentException.class,
                () -> new Draft("Fake", "https://curricula.live/knowledge/computing/concept/" + UUID.randomUUID()));
    }
}
