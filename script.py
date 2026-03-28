with open('src/main/java/com/yoswell/agenticrag/core/memory/store/HierarchicalChatMemoryStore.java', 'r', encoding='utf-8') as f:
    text = f.read()

target = 'log.info(\"L1 size exceeded, scheduling async L2 Summarization thread for session: {}\", memoryId);'
todo = 'log.info(\"L1 size exceeded, scheduling async L2 Summarization thread for session: {}\", memoryId);\n            // TODO: L2和L3压缩只是日志打印（假实现），并未真正起线程调用LLM进行摘要并存储。需接入轻量级模型进行后台异步提炼。'

if target in text:
    text = text.replace(target, todo)
    with open('src/main/java/com/yoswell/agenticrag/core/memory/store/HierarchicalChatMemoryStore.java', 'w', encoding='utf-8') as f:
        f.write(text)
    print('Inserted TODO in HierarchicalChatMemoryStore')
else:
    print('Target not found')
