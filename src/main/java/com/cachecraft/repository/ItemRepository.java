package com.cachecraft.repository;

import com.cachecraft.model.Item;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Owns item SQL and counts statements actually submitted to PostgreSQL. */
@Repository
public class ItemRepository {

    private static final String FIND_BY_ID_SQL = """
            WITH pause AS MATERIALIZED (
                SELECT pg_sleep(CAST(? AS double precision) / 1000.0)
            )
            SELECT i.id, i.name, i.price, i.description
            FROM pause
            CROSS JOIN items AS i
            WHERE i.id = ?
            """;
    private static final String FIND_ALL_SQL = """
            SELECT id, name, price, description
            FROM items
            ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong dbQueryCount = new AtomicLong();

    public ItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs the artificial delay in PostgreSQL after JDBC has acquired a pool
     * connection, so the configured delay occupies one HikariCP connection.
     *
     * @param id seeded item identifier
     * @param delayMillis database-side delay in milliseconds
     * @return the item when the identifier exists
     */
    public Optional<Item> findById(long id, long delayMillis) {
        return jdbcTemplate.execute((ConnectionCallback<Optional<Item>>) connection ->
                executeFindById(connection.prepareStatement(FIND_BY_ID_SQL), id, delayMillis));
    }

    /**
     * Reads the deterministic seed set used to prepopulate a synchronized
     * avalanche. This is one PostgreSQL statement, not 10,000 point lookups.
     */
    public List<Item> findAll() {
        return jdbcTemplate.execute((ConnectionCallback<List<Item>>) connection ->
                executeFindAll(connection.prepareStatement(FIND_ALL_SQL)));
    }

    /** Returns the number of item SELECT statements submitted by this process. */
    public long getDbQueryCount() {
        return dbQueryCount.get();
    }

    private Optional<Item> executeFindById(PreparedStatement statement, long id, long delayMillis)
            throws SQLException {
        try (statement) {
            statement.setLong(1, delayMillis);
            statement.setLong(2, id);
            dbQueryCount.incrementAndGet();

            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapItem(results));
            }
        }
    }

    private List<Item> executeFindAll(PreparedStatement statement) throws SQLException {
        try (statement) {
            dbQueryCount.incrementAndGet();
            try (ResultSet results = statement.executeQuery()) {
                List<Item> items = new ArrayList<>();
                while (results.next()) {
                    items.add(mapItem(results));
                }
                return List.copyOf(items);
            }
        }
    }

    private Item mapItem(ResultSet results) throws SQLException {
        return new Item(
                results.getLong("id"),
                results.getString("name"),
                results.getInt("price"),
                results.getString("description"));
    }
}
