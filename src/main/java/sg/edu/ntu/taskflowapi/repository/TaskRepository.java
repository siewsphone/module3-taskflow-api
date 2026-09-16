package sg.edu.ntu.taskflowapi.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import sg.edu.ntu.taskflowapi.model.Task;

@Repository
public class TaskRepository {

    /** Never null - initialised at construction. */
    private final Map<Long, Task> tasks = new HashMap<>();

    /** Simple counter that generates the next id. */
    private Long nextId = 1L;

    /** All tasks, as a List so callers cannot mutate our internal map. */
    public List<Task> findAll() {
        return new ArrayList<>(tasks.values());
    }

    /** Look a task up by its key. Empty if there is no such task. */
    public Optional<Task> findById(Long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public boolean existsById(Long id) {
        return tasks.containsKey(id);
    }

    /**
     * Insert or update.
     *
     * If the task has no id yet it is new, so we assign the next generated id.
     * Otherwise the id already exists and we are replacing that entry.
     * Returns the saved task so the caller sees the generated id.
     */
    public Task save(Task task) {
        if (task.getId() == null) {
            task.setId(nextId);
            nextId++;
        }
        tasks.put(task.getId(), task);
        return task;
    }

    public void deleteById(Long id) {
        tasks.remove(id);
    }

    public long count() {
        return tasks.size();
    }

}
