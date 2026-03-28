package com.yoswell.agenticrag.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yoswell.agenticrag.entity.ChatSession;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Optional<ChatSession> findBySessionId(String sessionId);
    Page<ChatSession> findByUserIdAndStatus(String userId, String status, Pageable pageable);
    List<ChatSession> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status);
}
