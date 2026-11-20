package com.example.chat.repository;

import com.example.chat.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Newest-first public history; callers pass PageRequest.of(0, limit). */
    @Query("SELECT m FROM Message m WHERE m.isPrivate = false ORDER BY m.timestamp DESC, m.id DESC")
    List<Message> findPublicHistory(Pageable pageable);

    /** Both directions of a 1:1 conversation, newest first. */
    @Query("""
            SELECT m FROM Message m
            WHERE (m.sender = :a AND m.receiver = :b)
               OR (m.sender = :b AND m.receiver = :a)
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findConversation(@Param("a") String a, @Param("b") String b, Pageable pageable);

    /**
     * PostgreSQL full-text search over public messages.
     * The to_tsvector expression matches the GIN index created in data.sql.
     */
    @Query(value = """
            SELECT * FROM messages m
            WHERE m.is_private = false
              AND to_tsvector('english', coalesce(m.content, '')) @@ plainto_tsquery('english', :query)
            ORDER BY m.timestamp DESC
            LIMIT 100
            """, nativeQuery = true)
    List<Message> searchPublic(@Param("query") String query);

    /** Full-text search scoped to one private conversation (authorization enforced in the service). */
    @Query(value = """
            SELECT * FROM messages m
            WHERE m.is_private = true
              AND ((m.sender = :me AND m.receiver = :other) OR (m.sender = :other AND m.receiver = :me))
              AND to_tsvector('english', coalesce(m.content, '')) @@ plainto_tsquery('english', :query)
            ORDER BY m.timestamp DESC
            LIMIT 100
            """, nativeQuery = true)
    List<Message> searchPrivate(@Param("me") String me, @Param("other") String other, @Param("query") String query);
}
