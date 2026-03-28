package com.yoswell.agenticrag.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_global_memory")
public class UserGlobalMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "preference_key", nullable = false, length = 255)
    private String preferenceKey;

    @Column(name = "preference_value", nullable = false, columnDefinition = "TEXT")
    private String preferenceValue;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPreferenceKey() { return preferenceKey; }
    public void setPreferenceKey(String preferenceKey) { this.preferenceKey = preferenceKey; }
    public String getPreferenceValue() { return preferenceValue; }
    public void setPreferenceValue(String preferenceValue) { this.preferenceValue = preferenceValue; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
