with open('plan_docs/PROGRESS.md', 'r', encoding='utf-8') as f:
    text = f.read()

target = '- ?? **Plan-and-Execute端点映射缺失**：PlanAndExecuteOrchestrator已实现，但在 AgentController.java 中遗漏了路由 @PostMapping(`/chat/plan-execute/stream`) 的绑定。已在 AgentController.java 中补充 TODO 注释。'
replacement = '- ? **Plan-and-Execute端点映射缺失已修复**：已经在 AgentController.java 中挂载了 @PostMapping("/chat/plan-execute/stream") 端点，且删除了相关的 // TODO 警告，注入了 PlanAndExecuteOrchestrator。另添加了文档 plan_and_execution_spec.md。'

text = text.replace(target, replacement)
with open('plan_docs/PROGRESS.md', 'w', encoding='utf-8') as f:
   f.write(text)
print('PROGRESS.md updated')
