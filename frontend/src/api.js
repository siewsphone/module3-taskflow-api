const BASE =
  import.meta.env.VITE_API_URL ??
  "https://module3-taskflow-api-production.up.railway.app";

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, options);
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = body.error ?? detail;
    } catch {
      /* non-JSON error body */
    }
    throw new Error(detail);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  list: () => request("/api/tasks"),

  create: (title) =>
    request("/api/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title, completed: false }),
    }),

  setCompleted: (id, completed) =>
    request(`/api/tasks/${id}/complete`, { method: "PUT" }),

  update: (id, task) =>
    request(`/api/tasks/${id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(task),
    }),

  remove: (id) => request(`/api/tasks/${id}`, { method: "DELETE" }),
};
