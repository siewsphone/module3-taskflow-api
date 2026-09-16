package sg.edu.ntu.taskflowapi.service;

import java.util.List;

import org.springframework.stereotype.Service;

import sg.edu.ntu.taskflowapi.model.Task;
import sg.edu.ntu.taskflowapi.repository.TaskRepository;


@Service
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public List<Task> findAllTasks() {
        return taskRepository.findAll();
    }


    public Task findTaskById(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    public Task createTask(Task task) {
        // The repository generates ids, so a client-supplied id is ignored.
        task.setId(null);
        normalise(task);
        return taskRepository.save(task);
    }

    /**
     * Update an existing task. Looks the task up first, so a bad id raises
     * TaskNotFoundException (-> 404) rather than silently creating a new task.
     */
    public Task updateTask(Long id, Task updated) {
        Task existing = findTaskById(id);
        normalise(updated);
        existing.setTitle(updated.getTitle());
        existing.setCompleted(updated.getCompleted());
        return taskRepository.save(existing);
    }

    public void deleteTask(Long id) {
        findTaskById(id); // 404 if it is not there
        taskRepository.deleteById(id);
    }

    /**
     * Mark a task complete: find it, set the flag to true, save it back.
     */
    public Task markTaskAsComplete(Long id) {
        Task task = findTaskById(id);
        task.setCompleted(true);
        return taskRepository.save(task);
    }

    /**
     * Guarantees a task's completed flag is never null before it is stored.
     *
     * An omitted field already defaults to false, but a client can explicitly
     * send "completed": null. Normalising incoming data is a business rule, so
     * it belongs here in the service - and it means the flag is always safe to
     * read once a task is in the repository.
     */
    private void normalise(Task task) {
        if (task.getCompleted() == null) {
            task.setCompleted(false);
        }
    }

}
