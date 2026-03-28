package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    private List<PlanStep> steps;

}
