package sg.edu.ntu.taskflowapi.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import sg.edu.ntu.taskflowapi.model.Task;
import sg.edu.ntu.taskflowapi.service.TaskService;
import sg.edu.ntu.taskflowapi.service.TaskSummaryService;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskSummaryService taskSummaryService;

    public TaskController(TaskService taskService, TaskSummaryService taskSummaryService) {
        this.taskService = taskService;
        this.taskSummaryService = taskSummaryService;
    }

    /** GET /api/tasks - all tasks -> 200. */
    @GetMapping
    public ResponseEntity<List<Task>> getAllTasks() {
        List<Task> tasks = taskService.findAllTasks();
        return new ResponseEntity<>(tasks, HttpStatus.OK);
    }

    /** GET /api/tasks/{id} - one task -> 200, or 404 via the exception handler. */
    @GetMapping("/{id}")
    public ResponseEntity<Task> getTaskById(@PathVariable Long id) {
        Task task = taskService.findTaskById(id);
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    /**
     * POST /api/tasks - create -> 201 CREATED.
     *
     * 201 is the point of this endpoint: the request succeeded AND created a new
     * resource. @RequestBody turns the incoming JSON into a Task via Jackson.
     */
    @PostMapping
    public ResponseEntity<Task> createTask(@RequestBody Task task) {
        Task created = taskService.createTask(task);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /** PUT /api/tasks/{id} - update -> 200. */
    @PutMapping("/{id}")
    public ResponseEntity<Task> updateTask(@PathVariable Long id, @RequestBody Task task) {
        Task updated = taskService.updateTask(id, task);
        return new ResponseEntity<>(updated, HttpStatus.OK);
    }

    /** DELETE /api/tasks/{id} - delete -> 200. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /** PUT /api/tasks/{id}/complete - mark complete -> 200. */
    @PutMapping("/{id}/complete")
    public ResponseEntity<Task> markTaskAsComplete(@PathVariable Long id) {
        Task completed = taskService.markTaskAsComplete(id);
        return new ResponseEntity<>(completed, HttpStatus.OK);
    }

    /**
     * TASK 6 - GET /api/tasks/summary -> 200 with a plain-English summary.
     *
     * Note the ordering question this creates: "/summary" is a literal path and
     * "/{id}" is a template. Spring MVC gives literal segments higher precedence,
     * so this method wins for /api/tasks/summary and the {id} method still
     * handles /api/tasks/1.
     */
    @GetMapping("/summary")
    public ResponseEntity<String> getSummary() {
        List<Task> tasks = taskService.findAllTasks();
        String summary = taskSummaryService.summarise(tasks);
        return new ResponseEntity<>(summary, HttpStatus.OK);
    }

    /**
     * GET /api/tasks/version -> 200 with the build version baked into the JAR.
     *
     * The Maven project version is copied into the manifest by the Spring Boot
     * plugin, so a running container can report which image tag it came from.
     * That is what makes versioned images (Option 4) verifiable at runtime
     * instead of only readable in the Docker Hub UI.
     */
    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> getVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return ResponseEntity.ok(Map.of("version", version == null ? "dev" : version));
    }

}
