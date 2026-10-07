package com.cachecraft.repository;

import com.cachecraft.model.Item;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void bindsDelayAndIdThenCountsImmediatelyBeforeQueryExecution() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet results = mock(ResultSet.class);
        ItemRepository repository = new ItemRepository(jdbcTemplate);

        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(invocation -> {
            assertEquals(1, repository.getDbQueryCount());
            return results;
        });
        when(results.next()).thenReturn(true);
        when(results.getLong("id")).thenReturn(42L);
        when(results.getString("name")).thenReturn("Item 42");
        when(results.getInt("price")).thenReturn(1654);
        when(results.getString("description")).thenReturn("description");
        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.any(ConnectionCallback.class)))
                .thenAnswer(invocation -> ((ConnectionCallback<?>) invocation.getArgument(0))
                        .doInConnection(connection));

        Optional<Item> result = repository.findById(42, 250);

        assertEquals(Optional.of(new Item(42, "Item 42", 1654, "description")), result);
        assertTrue(repository.getDbQueryCount() == 1);
        InOrder order = inOrder(statement);
        order.verify(statement).setLong(1, 250);
        order.verify(statement).setLong(2, 42);
        order.verify(statement).executeQuery();
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionAcquisitionFailureDoesNotIncrementQueryCounter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.any(ConnectionCallback.class)))
                .thenThrow(new CannotGetJdbcConnectionException("pool unavailable"));
        ItemRepository repository = new ItemRepository(jdbcTemplate);

        org.junit.jupiter.api.Assertions.assertThrows(CannotGetJdbcConnectionException.class,
                () -> repository.findById(42, 250));

        assertEquals(0, repository.getDbQueryCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyResultStillCountsExecutedQuery() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet results = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(results);
        when(results.next()).thenReturn(false);
        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.any(ConnectionCallback.class)))
                .thenAnswer(invocation -> ((ConnectionCallback<?>) invocation.getArgument(0))
                        .doInConnection(connection));
        ItemRepository repository = new ItemRepository(jdbcTemplate);

        assertTrue(repository.findById(10001, 0).isEmpty());
        assertEquals(1, repository.getDbQueryCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsTheSeedSetWithOneCountedStatement() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet results = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(results);
        when(results.next()).thenReturn(true, true, false);
        when(results.getLong("id")).thenReturn(1L);
        when(results.getString("name")).thenReturn("Item 1");
        when(results.getInt("price")).thenReturn(137);
        when(results.getString("description")).thenReturn("description");
        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.any(ConnectionCallback.class)))
                .thenAnswer(invocation -> ((ConnectionCallback<?>) invocation.getArgument(0))
                        .doInConnection(connection));
        ItemRepository repository = new ItemRepository(jdbcTemplate);

        List<Item> items = repository.findAll();

        assertEquals(2, items.size());
        assertEquals(1, repository.getDbQueryCount());
    }
}
