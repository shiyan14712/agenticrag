package com.yoswell.agenticrag.core.agent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {

    private String id;
    private String description;
    private String status; // PENDING, IN_PROGRESS, DONE, FAILED

}
