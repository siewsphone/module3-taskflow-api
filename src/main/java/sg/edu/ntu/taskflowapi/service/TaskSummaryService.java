package sg.edu.ntu.taskflowapi.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import sg.edu.ntu.taskflowapi.model.Task;


@Service
public class TaskSummaryService {

    private final ChatClient chatClient;

    public TaskSummaryService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }


    public String summarise(List<Task> tasks) {

        if (tasks.isEmpty()) {
            return "There are no tasks yet. Add one to get started.";
        }

        String pending = tasks.stream()
                .filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
                .map(Task::getTitle)
                .collect(Collectors.joining(", "));

        String done = tasks.stream()
                .filter(task -> Boolean.TRUE.equals(task.getCompleted()))
                .map(Task::getTitle)
                .collect(Collectors.joining(", "));

        String taskList = tasks.stream()
                .map(task -> "- " + task.getTitle() + " (completed: " + task.getCompleted() + ")")
                .collect(Collectors.joining("\n"));

        return chatClient.prompt()
                .system("""
                        You are a concise assistant for a task management API.
                        Summarise the user's tasks in plain English.
                        Say clearly what is pending and what has been done.
                        Keep it to a few short sentences.
                        """)
                .user("""
                        Here are my tasks:

                        %s

                        Pending: %s
                        Completed: %s

                        Summarise what is pending and what is done.
                        """.formatted(taskList,
                                      pending.isEmpty() ? "none" : pending,
                                      done.isEmpty() ? "none" : done))
                .call()
                .content();
    }

}
