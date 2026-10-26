import { createContext, useReducer } from "react";

export const TaskContext = createContext(null);

const initialState = { tasks: [], loading: true, error: null };

function reducer(state, action) {
  switch (action.type) {
    case "LOAD":
      return { ...state, tasks: action.tasks, loading: false, error: null };
    case "ADD":
      return { ...state, tasks: [...state.tasks, action.task] };
    case "UPDATE":
      return {
        ...state,
        tasks: state.tasks.map((t) =>
          t.id === action.task.id ? action.task : t,
        ),
      };
    case "REMOVE":
      return {
        ...state,
        tasks: state.tasks.filter((t) => t.id !== action.id),
      };
    case "ERROR":
      return { ...state, loading: false, error: action.message };
    default:
      return state;
  }
}

export function TaskProvider({ children }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  const total = state.tasks.length;
  const completed = state.tasks.filter((t) => t.completed).length;

  const value = {
    state,
    dispatch,
    total,
    completed,
    pending: total - completed,
  };

  return <TaskContext.Provider value={value}>{children}</TaskContext.Provider>;
}
