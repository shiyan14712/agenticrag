import os
import re
import shutil

base_dir = r"d:\Projects\Java\agenticrag\src\main\java\com\yoswell\agenticrag"
test_dir = r"d:\Projects\Java\agenticrag\src\test\java\com\yoswell\agenticrag"

moves = {
    "agent": "core/agent",
    "memory": "core/memory",
    "document": "retrieval/document",
    "session": "platform/session",
    "task": "platform/task",
    "security": "web/security"
}

for source, target in moves.items():
    target_path = os.path.join(base_dir, target)
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    source_path = os.path.join(base_dir, source)
    if os.path.exists(source_path):
        print(f"Moving {source} to {target}")
        shutil.move(source_path, target_path)
        
    test_target = os.path.join(test_dir, target)
    os.makedirs(os.path.dirname(test_target), exist_ok=True)
    test_source = os.path.join(test_dir, source)
    if os.path.exists(test_source):
        print(f"Moving test {source} to {target}")
        shutil.move(test_source, test_target)

def update_java_files(root_dir):
    if not os.path.exists(root_dir): return
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith(".java"):
                file_path = os.path.join(root, file)
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        content = f.read()
                    
                    new_content = content
                    for source, target in moves.items():
                        old_pkg = f"com.yoswell.agenticrag.{source}"
                        new_pkg = f"com.yoswell.agenticrag.{target.replace('/', '.')}"
                        new_content = re.sub(rf'\b{old_pkg}\b', new_pkg, new_content)
                        
                    if new_content != content:
                        with open(file_path, "w", encoding="utf-8") as f:
                            f.write(new_content)
                except Exception as e:
                    print(f"Failed to process {file_path}: {e}")

update_java_files(r"d:\Projects\Java\agenticrag\src\main\java")
update_java_files(r"d:\Projects\Java\agenticrag\src\test\java")

print("Refactor complete.")
