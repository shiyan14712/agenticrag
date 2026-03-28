package com.yoswell.agenticrag.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yoswell.agenticrag.entity.DocumentMetadata;

@Repository
public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long> {
    List<DocumentMetadata> findByTenantIdAndKbId(String tenantId, String kbId);
}
