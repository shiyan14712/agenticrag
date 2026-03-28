package com.yoswell.agenticrag.dto;

import java.util.List;

public class ExecutionPlan {

    private List<PlanStep> steps;

    // Default constructor for Jackson
    public ExecutionPlan() {}

    public ExecutionPlan(List<PlanStep> steps) {
        this.steps = steps;
    }

    public List<PlanStep> getSteps() {
        return steps;
    }

    public void setSteps(List<PlanStep> steps) {
        this.steps = steps;
    }
}
