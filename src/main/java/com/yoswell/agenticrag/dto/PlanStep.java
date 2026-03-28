package com.yoswell.agenticrag.dto;

public class PlanStep {
    
    private String id;
    private String description;
    
    // Default constructor for Jackson
    public PlanStep() {}

    public PlanStep(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
