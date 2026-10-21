import { useContext, useEffect, useState } from "react";
import { TaskContext, TaskProvider } from "./TaskContext.jsx";
import { api } from "./api.js";

function SummaryBar() {
  const { total, completed, pending } = useContext(TaskContext);
  return (
    <div className="summary">
      <span className="pill">{total} total</span>
      <span className="pill done">{completed} done</span>
      <span className="pill pending">{pending} to do</span>
    </div>
  );
}

function TaskForm() {
  const { dispatch } = useContext(TaskContext);
  const [title, setTitle] = useState("");

  async function handleSubmit(event) {
    event.preventDefault();
    const trimmed = title.trim();
    if (!trimmed) return;
    try {
      const created = await api.create(trimmed);
      dispatch({ type: "ADD", task: created });
      setTitle("");
    } catch (error) {
      dispatch({ type: "ERROR", message: error.message });
    }
  }

  return (
    <form className="task-form" onSubmit={handleSubmit}>
      <input
        type="text"
        value={title}
        placeholder="What needs doing?"
        onChange={(event) => setTitle(event.target.value)}
      />
      <button type="submit">Add</button>
    </form>
  );
}

function TaskItem({ task }) {
  const { dispatch } = useContext(TaskContext);

  async function toggleCompleted() {
    try {
      const updated = task.completed
        ? await api.update(task.id, { ...task, completed: false })
        : await api.setCompleted(task.id, true);
      dispatch({ type: "UPDATE", task: updated });
    } catch (error) {
      dispatch({ type: "ERROR", message: error.message });
    }
  }

  async function remove() {
    try {
      await api.remove(task.id);
      dispatch({ type: "REMOVE", id: task.id });
    } catch (error) {
      dispatch({ type: "ERROR", message: error.message });
    }
  }

  return (
    <li className={task.completed ? "task completed" : "task"}>
      <label>
        <input type="checkbox" checked={task.completed} onChange={toggleCompleted} />
        <span>{task.title}</span>
      </label>
      <button className="delete" onClick={remove} aria-label="Delete task">
        Delete
      </button>
    </li>
  );
}

function TaskList() {
  const { state } = useContext(TaskContext);

  if (state.loading) {
    return <p className="note">Loading tasks…</p>;
  }
  if (state.tasks.length === 0) {
    return <p className="note">No tasks yet. Add your first one above.</p>;
  }
  return (
    <ul className="task-list">
      {state.tasks.map((task) => (
        <TaskItem key={task.id} task={task} />
      ))}
    </ul>
  );
}

function ErrorBanner() {
  const { state, dispatch } = useContext(TaskContext);
  if (!state.error) return null;
  return (
    <div className="error">
      <span>{state.error} — the API may be busy, please try again.</span>
      <button onClick={() => dispatch({ type: "LOAD", tasks: [] })}>Dismiss</button>
    </div>
  );
}

function TaskFlowApp() {
  const { state, dispatch } = useContext(TaskContext);

  useEffect(() => {
    let alive = true;
    api
      .list()
      .then((tasks) => {
        if (alive) dispatch({ type: "LOAD", tasks });
      })
      .catch((error) => {
        if (alive) dispatch({ type: "ERROR", message: error.message });
      });
    return () => {
      alive = false;
    };
  }, [dispatch]);

  return (
    <main className="app">
      <header>
        <h1>TaskFlow</h1>
        <SummaryBar />
      </header>
      <ErrorBanner />
      <TaskForm />
      <TaskList />
      <footer>{state.tasks.length > 0 ? "" : " "}</footer>
    </main>
  );
}

export default function App() {
  return (
    <TaskProvider>
      <TaskFlowApp />
    </TaskProvider>
  );
}
