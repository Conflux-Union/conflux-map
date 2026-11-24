export const selectedServer = new URL(location.href).searchParams.get(
  'serverId'
);
export function apiPath(path, id = selectedServer) {
  if (!id) return path;
  return `${path}${path.includes('?') ? '&' : '?'}serverId=${encodeURIComponent(id)}`;
}
export async function chooseServer() {
  try {
    const r = await fetch('/api/v1/servers', { cache: 'no-store' });
    if (!r.ok) return;
    const servers = await r.json();
    if (!Array.isArray(servers) || !servers.length) return;
    const active = servers.find((s) => s.id === selectedServer);
    if (!active) {
      const url = new URL(location.href);
      url.searchParams.set('serverId', servers[0].id);
      location.replace(url);
      return new Promise(() => {});
    }
    const select = document.createElement('select');
    select.setAttribute('aria-label', 'Server');
    for (const s of servers) {
      const o = document.createElement('option');
      o.value = s.id;
      o.textContent = s.name;
      select.append(o);
    }
    select.value = active.id;
    select.addEventListener('change', () => {
      const url = new URL(location.href);
      url.searchParams.set('serverId', select.value);
      location.assign(url);
    });
    document.querySelector('.controls').append(select);
    setInterval(async () => {
      try {
        const next = await fetch('/api/v1/servers', { cache: 'no-store' }).then(
          (r) => r.json()
        );
        if (!Array.isArray(next)) return;
        const old = select.value;
        select.replaceChildren();
        for (const s of next) {
          const o = document.createElement('option');
          o.value = s.id;
          o.textContent = s.name;
          select.append(o);
        }
        if (!next.some((s) => s.id === old)) {
          const o = document.createElement('option');
          o.value = old;
          o.textContent = active.name + ' (offline)';
          select.append(o);
        }
        select.value = old;
      } catch {}
    }, 5000);
  } catch {}
}
export function installWebExtras({
  manifest,
  message,
  send,
  onPolicy,
  onDark
}) {
  const root = document.createElement('section');
  root.className = 'web-chat';
  root.hidden = true;
  const header = document.createElement('button');
  header.type = 'button';
  header.className = 'web-chat-head';
  header.textContent = message('chat');
  header.setAttribute('aria-expanded', 'false');
  const body = document.createElement('div');
  body.className = 'web-chat-body';
  body.hidden = true;
  const history = document.createElement('div');
  history.className = 'web-chat-history';
  history.setAttribute('role', 'log');
  const form = document.createElement('form');
  const input = document.createElement('input');
  input.maxLength = 256;
  input.placeholder = message('chatInput');
  const submit = document.createElement('button');
  submit.type = 'submit';
  submit.textContent = message('chatSend');
  form.append(input, submit);
  const feedback = document.createElement('small');
  feedback.setAttribute('role', 'status');
  body.append(history, form, feedback);
  root.append(header, body);
  document.body.append(root);
  let last = null,
    unread = 0,
    policy = {},
    images = new Map();
  header.addEventListener('click', () => {
    body.hidden = !body.hidden;
    header.setAttribute('aria-expanded', String(!body.hidden));
    unread = 0;
    header.textContent = message('chat');
    history.scrollTop = history.scrollHeight;
  });
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    if (!input.value.trim() || !policy.allowWebChat) return;
    try {
      send(JSON.stringify({ type: 'chat-send', text: input.value }));
      input.value = '';
      feedback.textContent = '';
    } catch {
      feedback.textContent = message('connecting');
    }
  });
  const darkLabel = document.createElement('label');
  darkLabel.className = 'prediction-dark-toggle';
  const dark = document.createElement('input');
  dark.type = 'checkbox';
  const key = `conflux-map-dark:${manifest.presentation?.serverInstanceId ?? 'legacy'}:${manifest.worldId}`;
  try {
    dark.checked = localStorage.getItem(key) === 'true';
  } catch {}
  darkLabel.append(dark, document.createTextNode(message('predictionDark')));
  darkLabel.hidden = !manifest.predictionAvailable;
  document.querySelector('.controls').append(darkLabel);
  dark.addEventListener('change', () => {
    try {
      localStorage.setItem(key, String(dark.checked));
    } catch {}
    onDark(dark.checked);
  });
  function receive(m) {
    if (m.type === 'web-policy') {
      policy = m;
      root.hidden = !m.enableChat;
      form.hidden = !m.allowWebChat;
      if (!m.enableChat) {
        history.replaceChildren();
        last = null;
        unread = 0;
        header.textContent = message('chat');
      }
      if (!m.showWaypointImages) images.clear();
      onPolicy(m);
      return true;
    }
    if (m.type === 'chat-history') {
      const rows = m.messages ?? [];
      const bottom =
        history.scrollHeight - history.scrollTop - history.clientHeight < 24;
      const nextLast = rows.at(-1)?.id ?? 0;
      if (body.hidden && last !== null && nextLast > last) {
        unread += rows.filter((r) => r.id > last).length;
        header.textContent = `${message('chat')} (${unread})`;
      }
      last = nextLast;
      history.replaceChildren();
      for (const r of rows) {
        const row = document.createElement('div');
        row.className = 'web-chat-row';
        if (r.source === 'game') {
          const avatar = document.createElement('img');
          avatar.alt = '';
          avatar.src = apiPath(
            `/api/v1/avatars/${encodeURIComponent(r.senderId)}.png`,
            manifest.presentation?.serverInstanceId
          );
          avatar.addEventListener(
            'error',
            () => (avatar.src = '/default-steve.svg'),
            { once: true }
          );
          row.append(avatar);
        }
        const text = document.createElement('div');
        const name = document.createElement('strong');
        name.textContent =
          r.senderName +
          ' · ' +
          new Date(r.timestamp).toLocaleTimeString([], {
            hour: '2-digit',
            minute: '2-digit'
          });
        const p = document.createElement('p');
        p.textContent = r.text;
        text.append(name, p);
        row.append(text);
        history.append(row);
      }
      if (bottom) history.scrollTop = history.scrollHeight;
      return true;
    }
    if (m.type === 'chat-result') {
      feedback.textContent = m.message ?? '';
      return true;
    }
    if (m.type === 'waypoint-images') {
      images = new Map();
      for (const im of m.images ?? []) {
        if (!images.has(im.waypoint)) images.set(im.waypoint, []);
        images.get(im.waypoint).push(im.id);
      }
      onPolicy(policy);
      return true;
    }
    return false;
  }
  function popup(waypoint) {
    const root = document.createElement('div');
    const title = document.createElement('strong');
    title.textContent = waypoint.name;
    root.append(title);
    for (const id of images.get(waypoint.id) ?? []) {
      const img = document.createElement('img');
      img.className = 'waypoint-photo';
      img.loading = 'lazy';
      img.alt = waypoint.name;
      img.src = apiPath(
        `/api/v1/waypoint-images/${encodeURIComponent(id)}.png`,
        manifest.presentation?.serverInstanceId
      );
      root.append(img);
    }
    return root;
  }
  return { receive, popup, dark: () => dark.checked };
}
