package com.tieat.identity.adapter.out.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSessionAuthenticationStateStore {
    private final JdbcTemplate jdbcTemplate;
    public JdbcSessionAuthenticationStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }
    public Optional<SessionRow> lock(String sessionId) {
        return jdbcTemplate.query(
            "SELECT primary_id, expiry_time, principal_name FROM spring_session WHERE session_id = ? FOR UPDATE",
            (resultSet, rowNum) -> new SessionRow(resultSet.getString(1), resultSet.getLong(2), resultSet.getString(3)),
            sessionId
        ).stream().findFirst();
    }
    public Object readAttribute(SessionRow row, String name) {
        List<byte[]> values = jdbcTemplate.query(
            "SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id = ? AND attribute_name = ?",
            (resultSet, rowNum) -> resultSet.getBytes(1),
            row.primaryId(), name
        );
        return values.isEmpty() ? null : deserialize(values.get(0));
    }
    public void upsertAttribute(SessionRow row, String name, Object value) {
        jdbcTemplate.update(
            """
            INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
            VALUES (?, ?, ?)
            ON CONFLICT (session_primary_id, attribute_name)
            DO UPDATE SET attribute_bytes = EXCLUDED.attribute_bytes
            """,
            row.primaryId(), name, serialize(value)
        );
    }
    public void deleteAttribute(SessionRow row, String name) {
        jdbcTemplate.update(
            "DELETE FROM spring_session_attributes WHERE session_primary_id = ? AND attribute_name = ?",
            row.primaryId(), name
        );
    }
    private byte[] serialize(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(value);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Session attribute could not be serialized", exception);
        }
    }
    private Object deserialize(byte[] bytes) {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return input.readObject();
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Session attribute could not be deserialized", exception);
        }
    }
    public record SessionRow(String primaryId, long expiryTime, String principalName) {
    }
}
