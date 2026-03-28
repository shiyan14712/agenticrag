package com.yoswell.agenticrag.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yoswell.agenticrag.entity.UserGlobalMemory;

@Repository
public interface UserGlobalMemoryRepository extends JpaRepository<UserGlobalMemory, Long> {
    List<UserGlobalMemory> findByUserId(String userId);
}
